package main

import (
	"context"
	"fmt"
	"io"
	"log"
	"net"
	"strings"
	"sync"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/metadata"

	pb "chat-server/chat"
)

// --- Modelo de Actor para la concurrencia de la Sala de Texto ---

type client struct {
	stream pb.ChatService_JoinChatRoomServer
	sender string
	err    chan error
}
type roomCommand interface{}
type joinCommand struct{ client *client }
type leaveCommand struct{ client *client }
type broadcastCommand struct{ msg *pb.ChatMessage }
type directMessageCommand struct {
	msg       *pb.ChatMessage
	recipient string
}

type Room struct {
	roomID        string
	clients       map[*client]struct{}
	commands      chan roomCommand
	activeNames   map[string]bool // Nombres normalizados activos en la sala
	activeNamesMu sync.Mutex
}

func NewRoom(roomID string) *Room {
	r := &Room{
		roomID:      roomID,
		clients:     make(map[*client]struct{}),
		commands:    make(chan roomCommand),
		activeNames: make(map[string]bool),
	}
	go r.run()
	return r
}

func (r *Room) run() {
	log.Printf("Actor de la sala de texto '%s' iniciado.", r.roomID)
	for cmd := range r.commands {
		switch c := cmd.(type) {
		case joinCommand:
			r.clients[c.client] = struct{}{}
			// Registrar nombre normalizado
			r.activeNamesMu.Lock()
			r.activeNames[strings.ToLower(c.client.sender)] = true
			r.activeNamesMu.Unlock()

			log.Printf("Cliente '%s' se unió a la sala de texto '%s'. Clientes totales: %d", c.client.sender, r.roomID, len(r.clients))
			joinMsg := &pb.ChatMessage{
				Sender: "Servidor",
				Message: fmt.Sprintf("%s se ha unido a la sala.", c.client.sender),
				RoomId: r.roomID,
				Timestamp: time.Now().Unix(),
			}
			r.broadcast(joinMsg, c.client)
		case leaveCommand:
			if _, ok := r.clients[c.client]; ok {
				delete(r.clients, c.client)
				// Desregistrar nombre
				r.activeNamesMu.Lock()
				delete(r.activeNames, strings.ToLower(c.client.sender))
				r.activeNamesMu.Unlock()

				log.Printf("Cliente '%s' salió de la sala de texto '%s'. Clientes restantes: %d", c.client.sender, r.roomID, len(r.clients))
				leaveMsg := &pb.ChatMessage{
					Sender: "Servidor",
					Message: fmt.Sprintf("%s ha salido de la sala.", c.client.sender),
					RoomId: r.roomID,
					Timestamp: time.Now().Unix(),
				}
				r.broadcast(leaveMsg, c.client)
			}
		case broadcastCommand:
			log.Printf("[TraceID: %s] Retransmitiendo mensaje en la sala de texto '%s' de '%s'", c.msg.TraceId, r.roomID, c.msg.Sender)
			r.broadcast(c.msg, nil)
		case directMessageCommand:
			log.Printf("[TraceID: %s] Enviando mensaje directo a '%s' en sala '%s'", c.msg.TraceId, c.recipient, r.roomID)
			r.sendDirect(c.msg, c.recipient)
		}
	}
}

func (r *Room) broadcast(msg *pb.ChatMessage, originalSender *client) {
	for c := range r.clients {
		if c == originalSender {
			continue
		}
		if err := c.stream.Send(msg); err != nil {
			log.Printf("[TraceID: %s] Error al enviar mensaje a '%s': %v", msg.TraceId, c.sender, err)
		}
	}
}

func (r *Room) sendDirect(msg *pb.ChatMessage, recipient string) {
	for c := range r.clients {
		if c.sender == recipient {
			if err := c.stream.Send(msg); err != nil {
				log.Printf("[TraceID: %s] Error al enviar mensaje directo a '%s': %v", msg.TraceId, c.sender, err)
			}
			return
		}
	}
	log.Printf("[TraceID: %s] Cliente '%s' no encontrado en sala '%s'", msg.TraceId, recipient, r.roomID)
}

func (r *Room) isNameTaken(name string) bool {
	r.activeNamesMu.Lock()
	defer r.activeNamesMu.Unlock()
	return r.activeNames[strings.ToLower(name)]
}

// --- Servidor gRPC principal ---

// Información de transferencia de archivo pendiente
type fileTransfer struct {
	request        *pb.FileTransferRequest
	senderStream   pb.ChatService_TransferFileServer
	receiverStream pb.ChatService_TransferFileServer
	accepted       bool
}

type server struct {
	pb.UnimplementedChatServiceServer
	textRooms      map[string]*Room
	textMu         sync.Mutex
	audioStreams   map[string]map[string]pb.ChatService_StreamAudioServer
	audioMu        sync.Mutex
	fileTransfers  map[string]*fileTransfer // transfer_id -> transfer info
	transferMu     sync.Mutex
	// Map para enrutar solicitudes y respuestas: room_id -> sender -> channel
	transferRequests  map[string]map[string]chan *pb.FileTransferRequest
	transferResponses map[string]map[string]chan *pb.FileTransferResponse
	transferReqMu     sync.Mutex
	transferRespMu    sync.Mutex
}

func newServer() *server {
	return &server{
		textRooms:         make(map[string]*Room),
		audioStreams:      make(map[string]map[string]pb.ChatService_StreamAudioServer),
		fileTransfers:     make(map[string]*fileTransfer),
		transferRequests:  make(map[string]map[string]chan *pb.FileTransferRequest),
		transferResponses: make(map[string]map[string]chan *pb.FileTransferResponse),
	}
}

func (s *server) getOrCreateTextRoom(roomID string) *Room {
	s.textMu.Lock()
	defer s.textMu.Unlock()
	if r, ok := s.textRooms[roomID]; ok {
		return r
	}
	r := NewRoom(roomID)
	s.textRooms[roomID] = r
	return r
}

func (s *server) JoinChatRoom(stream pb.ChatService_JoinChatRoomServer) error {
	msg, err := stream.Recv()
	if err != nil {
		log.Printf("Error al recibir el primer mensaje de texto: %v", err)
		return err
	}
	log.Printf("[TraceID: %s] Solicitud de unión a sala de texto recibida de %s para la sala %s", msg.TraceId, msg.Sender, msg.RoomId)

	room := s.getOrCreateTextRoom(msg.RoomId)

	// Verificar si el nombre ya está en uso
	if room.isNameTaken(msg.Sender) {
		log.Printf("Nombre '%s' ya está en uso en la sala '%s'", msg.Sender, msg.RoomId)
		// Enviar mensaje de error al cliente
		errorMsg := &pb.ChatMessage{
			Sender:    "Servidor",
			Message:   fmt.Sprintf("ERROR:NAME_TAKEN:El nombre '%s' ya está en uso en esta sala.", msg.Sender),
			RoomId:    msg.RoomId,
			Timestamp: time.Now().Unix(),
			TraceId:   msg.TraceId,
		}
		stream.Send(errorMsg)
		return fmt.Errorf("nombre '%s' ya está en uso", msg.Sender)
	}

	c := &client{stream: stream, sender: msg.Sender, err: make(chan error)}
	room.commands <- joinCommand{client: c}
	go s.handleClientMessages(room, c)
	return <-c.err
}

func (s *server) handleClientMessages(room *Room, c *client) {
	for {
		msg, err := c.stream.Recv()
		if err == io.EOF {
			room.commands <- leaveCommand{client: c}
			c.err <- nil
			return
		}
		if err != nil {
			log.Printf("Error al recibir mensaje de texto de '%s': %v", c.sender, err)
			room.commands <- leaveCommand{client: c}
			c.err <- err
			return
		}
		room.commands <- broadcastCommand{msg: msg}
	}
}

// --- Lógica para el Stream de Audio ---

func (s *server) StreamAudio(stream pb.ChatService_StreamAudioServer) error {
	// Extraer metadatos del contexto del stream
	md, ok := metadata.FromIncomingContext(stream.Context())
	if !ok {
		log.Printf("AUDIO: Error: No se encontraron metadatos en el stream de audio.")
		return fmt.Errorf("no se encontraron metadatos")
	}

	roomIDs := md.Get("room-id")
	senders := md.Get("sender")

	if len(roomIDs) == 0 || len(senders) == 0 {
		log.Printf("AUDIO: Error: Metadatos 'room-id' o 'sender' faltantes en el stream de audio.")
		return fmt.Errorf("metadatos 'room-id' o 'sender' faltantes")
	}

	roomID := roomIDs[0]
	sender := senders[0]
	log.Printf("AUDIO: Stream de audio iniciado para %s en la sala %s (desde metadatos).", sender, roomID)

	// Registrar el stream de audio del cliente
	s.audioMu.Lock()
	if _, ok := s.audioStreams[roomID]; !ok {
		s.audioStreams[roomID] = make(map[string]pb.ChatService_StreamAudioServer)
	}
	s.audioStreams[roomID][sender] = stream
	s.audioMu.Unlock()
	log.Printf("AUDIO: Cliente %s registrado para audio en la sala %s. Clientes activos: %d", sender, roomID, len(s.audioStreams[roomID]))

	// Quitar el stream al final
	defer func() {
		s.audioMu.Lock()
		if room, ok := s.audioStreams[roomID]; ok {
			delete(room, sender)
			if len(room) == 0 {
				delete(s.audioStreams, roomID)
				log.Printf("AUDIO: Sala de audio %s ahora vacía y eliminada.", roomID)
			}
		}
		s.audioMu.Unlock()
		log.Printf("AUDIO: Stream de audio cerrado para %s en la sala %s. Clientes restantes: %d", sender, roomID, len(s.audioStreams[roomID]))
	}()

	// Bucle para retransmitir los paquetes de audio
	for {
		chunk, err := stream.Recv()
		if err == io.EOF {
			log.Printf("AUDIO: Cliente %s cerró su stream de audio para la sala %s (EOF).", sender, roomID)
			return nil // Salida elegante
		}
		if err != nil {
			log.Printf("AUDIO: Error al recibir stream de audio de %s en la sala %s: %v", sender, roomID, err)
			return err
		}
		// log.Printf("AUDIO: Recibido chunk de audio de %s en %s. Tamaño: %d bytes", sender, roomID, len(chunk.Data)) // Demasiado verboso

		s.audioMu.Lock()
		for otherSender, otherStream := range s.audioStreams[roomID] {
			if sender == otherSender {
				continue
			}
			if err := otherStream.Send(chunk); err != nil {
				log.Printf("AUDIO: Error al enviar audio a %s en %s: %v", otherSender, roomID, err)
				// Considerar remover al cliente si falla el envío continuamente
			}
		}
		s.audioMu.Unlock()
	}
}


// --- Lógica para Transferencia de Archivos ---

// RequestFileTransfer maneja solicitudes de transferencia de archivos
func (s *server) RequestFileTransfer(ctx context.Context, req *pb.FileTransferRequest) (*pb.FileTransferResponse, error) {
	log.Printf("FILE: Solicitud de transferencia de archivo de %s a %s en sala %s. Archivo: %s (%d bytes)",
		req.Sender, req.Recipient, req.RoomId, req.Filename, req.FileSize)

	// Enviar notificación al destinatario a través del chat
	s.textMu.Lock()
	room, ok := s.textRooms[req.RoomId]
	s.textMu.Unlock()

	if !ok {
		log.Printf("FILE: Error - sala %s no existe", req.RoomId)
		return &pb.FileTransferResponse{
			TransferId: req.TransferId,
			Accepted:   false,
			Sender:     req.Recipient,
			Recipient:  req.Sender,
			RoomId:     req.RoomId,
		}, nil
	}

	// Enviar mensaje de notificación al destinatario
	notificationMsg := &pb.ChatMessage{
		Sender:    "Sistema-FileTransfer",
		Message:   fmt.Sprintf("FILE_REQUEST:%s:%s:%s:%d:%d", req.TransferId, req.Sender, req.Filename, req.FileSize, req.Timestamp),
		RoomId:    req.RoomId,
		Timestamp: time.Now().Unix(),
		TraceId:   req.TransferId,
	}

	// Enviar solo al destinatario
	room.commands <- directMessageCommand{msg: notificationMsg, recipient: req.Recipient}

	// Guardar info de la transferencia
	s.transferMu.Lock()
	s.fileTransfers[req.TransferId] = &fileTransfer{
		request:  req,
		accepted: false,
	}
	s.transferMu.Unlock()

	// Esperar respuesta del destinatario con timeout
	s.transferRespMu.Lock()
	if _, ok := s.transferResponses[req.RoomId]; !ok {
		s.transferResponses[req.RoomId] = make(map[string]chan *pb.FileTransferResponse)
	}
	if s.transferResponses[req.RoomId][req.Sender] == nil {
		s.transferResponses[req.RoomId][req.Sender] = make(chan *pb.FileTransferResponse, 10)
	}
	senderRespChan := s.transferResponses[req.RoomId][req.Sender]
	s.transferRespMu.Unlock()

	select {
	case resp := <-senderRespChan:
		log.Printf("FILE: Respuesta recibida para transferencia %s: accepted=%v", req.TransferId, resp.Accepted)
		return resp, nil
	case <-time.After(60 * time.Second):
		log.Printf("FILE: Timeout esperando respuesta para transferencia %s", req.TransferId)
		return &pb.FileTransferResponse{
			TransferId: req.TransferId,
			Accepted:   false,
			Sender:     req.Recipient,
			Recipient:  req.Sender,
			RoomId:     req.RoomId,
		}, nil
	}
}

// RespondFileTransfer maneja respuestas a solicitudes de transferencia
func (s *server) RespondFileTransfer(ctx context.Context, resp *pb.FileTransferResponse) (*pb.FileTransferResponse, error) {
	log.Printf("FILE: Respuesta de %s para transferencia %s: accepted=%v", resp.Sender, resp.TransferId, resp.Accepted)

	// Actualizar estado de la transferencia
	s.transferMu.Lock()
	if transfer, ok := s.fileTransfers[resp.TransferId]; ok {
		transfer.accepted = resp.Accepted
	}
	s.transferMu.Unlock()

	// Enviar respuesta al sender original
	s.transferRespMu.Lock()
	if room, ok := s.transferResponses[resp.RoomId]; ok {
		if ch, ok := room[resp.Recipient]; ok && ch != nil {
			select {
			case ch <- resp:
				log.Printf("FILE: Respuesta enviada a %s", resp.Recipient)
			default:
				log.Printf("FILE: Error - canal de respuesta lleno para %s", resp.Recipient)
			}
		}
	}
	s.transferRespMu.Unlock()

	return resp, nil
}

// TransferFile maneja el stream bidireccional de chunks de archivo
func (s *server) TransferFile(stream pb.ChatService_TransferFileServer) error {
	// Obtener metadatos para identificar si es sender o receiver
	md, ok := metadata.FromIncomingContext(stream.Context())
	if !ok {
		return fmt.Errorf("no se encontraron metadatos")
	}

	roles := md.Get("role")
	transferIDs := md.Get("transfer-id")

	if len(roles) == 0 || len(transferIDs) == 0 {
		return fmt.Errorf("metadatos 'role' o 'transfer-id' faltantes")
	}

	role := roles[0]       // "sender" o "receiver"
	transferID := transferIDs[0]

	log.Printf("FILE: Stream de transferencia iniciado para transfer_id=%s, role=%s", transferID, role)

	// Verificar que la transferencia existe y está aceptada
	s.transferMu.Lock()
	transfer, ok := s.fileTransfers[transferID]
	if !ok {
		s.transferMu.Unlock()
		log.Printf("FILE: Error - transferencia %s no encontrada", transferID)
		return fmt.Errorf("transferencia no encontrada")
	}

	if role == "sender" {
		// Registrar stream del sender
		transfer.senderStream = stream
		s.transferMu.Unlock()

		log.Printf("FILE: Sender conectado para %s, esperando receiver...", transferID)

		// Esperar a que el receiver se conecte
		timeout := time.After(30 * time.Second)
		ticker := time.NewTicker(100 * time.Millisecond)
		defer ticker.Stop()

		for {
			select {
			case <-timeout:
				log.Printf("FILE: Timeout esperando receiver para %s", transferID)
				return fmt.Errorf("timeout esperando receiver")
			case <-ticker.C:
				s.transferMu.Lock()
				if transfer.receiverStream != nil {
					s.transferMu.Unlock()
					goto StartSending
				}
				s.transferMu.Unlock()
			}
		}

	StartSending:
		log.Printf("FILE: Receiver conectado, iniciando envío de chunks para %s", transferID)

		// Leer y retransmitir chunks
		for {
			chunk, err := stream.Recv()
			if err == io.EOF {
				log.Printf("FILE: Sender cerró stream para %s", transferID)
				break
			}
			if err != nil {
				log.Printf("FILE: Error recibiendo chunk del sender: %v", err)
				return err
			}

			// Enviar al receiver
			s.transferMu.Lock()
			receiverStream := transfer.receiverStream
			s.transferMu.Unlock()

			if receiverStream != nil {
				if err := receiverStream.Send(chunk); err != nil {
					log.Printf("FILE: Error enviando chunk al receiver: %v", err)
					return err
				}
			}

			if chunk.IsLast {
				log.Printf("FILE: Último chunk enviado para %s", transferID)
				break
			}
		}

		// Limpiar
		s.transferMu.Lock()
		delete(s.fileTransfers, transferID)
		s.transferMu.Unlock()

		return nil

	} else if role == "receiver" {
		// Registrar stream del receiver
		transfer.receiverStream = stream
		s.transferMu.Unlock()

		log.Printf("FILE: Receiver conectado para %s, esperando chunks...", transferID)

		// El receiver solo espera chunks, no envía nada
		// Los chunks llegarán a través de receiverStream.Send() llamado por el sender
		select {}

	} else {
		s.transferMu.Unlock()
		return fmt.Errorf("rol desconocido: %s", role)
	}
}

// --- Funciones de Inicialización ---

func main() {
	lis, err := net.Listen("tcp", ":50051")
	if err != nil {
		log.Fatalf("Error al escuchar: %v", err)
	}
	s := grpc.NewServer()
	pb.RegisterChatServiceServer(s, newServer())
	log.Printf("Servidor gRPC escuchando en %v", lis.Addr())
	if err := s.Serve(lis); err != nil {
		log.Fatalf("Error al servir: %v", err)
	}
}
