package main

import (
	"context"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/google/uuid"
	"google.golang.org/grpc/metadata"

	pb "go-client/chat"
)

const CHUNK_SIZE = 64 * 1024 // 64KB chunks

type FileTransferManager struct {
	client              pb.ChatServiceClient
	sender              string
	roomID              string
	pendingRequests     map[string]*pb.FileTransferRequest // transfer_id -> request
	pendingMu           sync.Mutex
	activeTransfers     map[string]bool // transfer_id -> active
	activeMu            sync.Mutex
	requestChannel      chan *pb.FileTransferRequest
	downloadDir         string
}

func NewFileTransferManager(client pb.ChatServiceClient, sender, roomID string) *FileTransferManager {
	homeDir, _ := os.UserHomeDir()
	downloadDir := filepath.Join(homeDir, "Descargas", "chat-downloads")
	os.MkdirAll(downloadDir, 0755)

	ftm := &FileTransferManager{
		client:          client,
		sender:          sender,
		roomID:          roomID,
		pendingRequests: make(map[string]*pb.FileTransferRequest),
		activeTransfers: make(map[string]bool),
		requestChannel:  make(chan *pb.FileTransferRequest, 10),
		downloadDir:     downloadDir,
	}

	// Iniciar goroutine para escuchar solicitudes entrantes
	go ftm.listenForRequests()

	return ftm
}

func (ftm *FileTransferManager) printMessage(message string) {
	fmt.Printf("\r\x1b[2K%s\n[%s] Tú: ", message, time.Now().Format("15:04"))
}

// listenForRequests escucha solicitudes de transferencia entrantes
func (ftm *FileTransferManager) listenForRequests() {
	for req := range ftm.requestChannel {
		ftm.printMessage(fmt.Sprintf("📁 %s quiere enviarte '%s' (%.2f MB)",
			req.Sender, req.Filename, float64(req.FileSize)/(1024*1024)))
		ftm.printMessage("Escribe /accept para aceptar o /cancel para rechazar")

		ftm.pendingMu.Lock()
		ftm.pendingRequests[req.TransferId] = req
		ftm.pendingMu.Unlock()
	}
}

// SendFile envía un archivo a un destinatario
func (ftm *FileTransferManager) SendFile(filePath, recipient string) error {
	// Verificar que el archivo existe
	fileInfo, err := os.Stat(filePath)
	if err != nil {
		return fmt.Errorf("error al acceder al archivo: %v", err)
	}

	if fileInfo.IsDir() {
		return fmt.Errorf("no se pueden enviar directorios")
	}

	transferID := uuid.New().String()
	filename := filepath.Base(filePath)

	ftm.printMessage(fmt.Sprintf("Solicitando enviar '%s' a %s...", filename, recipient))

	// Crear solicitud de transferencia
	req := &pb.FileTransferRequest{
		Sender:     ftm.sender,
		Recipient:  recipient,
		RoomId:     ftm.roomID,
		Filename:   filename,
		FileSize:   fileInfo.Size(),
		TransferId: transferID,
		Timestamp:  time.Now().Unix(),
	}

	// Enviar solicitud al servidor
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	resp, err := ftm.client.RequestFileTransfer(ctx, req)
	if err != nil {
		return fmt.Errorf("error al solicitar transferencia: %v", err)
	}

	if !resp.Accepted {
		ftm.printMessage(fmt.Sprintf("❌ %s rechazó la transferencia de '%s'", recipient, filename))
		return nil
	}

	ftm.printMessage(fmt.Sprintf("✅ %s aceptó la transferencia. Enviando...", recipient))

	// Iniciar envío del archivo
	return ftm.streamFileSend(filePath, transferID)
}

// streamFileSend envía el archivo en chunks
func (ftm *FileTransferManager) streamFileSend(filePath, transferID string) error {
	file, err := os.Open(filePath)
	if err != nil {
		return fmt.Errorf("error al abrir archivo: %v", err)
	}
	defer file.Close()

	// Crear contexto con metadatos
	ctx := context.Background()
	ctx = metadata.AppendToOutgoingContext(ctx,
		"role", "sender",
		"transfer-id", transferID,
	)

	stream, err := ftm.client.TransferFile(ctx)
	if err != nil {
		return fmt.Errorf("error al iniciar stream de transferencia: %v", err)
	}

	buffer := make([]byte, CHUNK_SIZE)
	chunkNumber := int32(0)
	totalSent := int64(0)
	fileInfo, _ := file.Stat()
	fileSize := fileInfo.Size()

	for {
		n, err := file.Read(buffer)
		if err == io.EOF {
			break
		}
		if err != nil {
			return fmt.Errorf("error al leer archivo: %v", err)
		}

		chunk := &pb.FileChunk{
			TransferId:  transferID,
			Data:        buffer[:n],
			ChunkNumber: chunkNumber,
			IsLast:      false,
		}

		if err := stream.Send(chunk); err != nil {
			return fmt.Errorf("error al enviar chunk: %v", err)
		}

		chunkNumber++
		totalSent += int64(n)

		// Mostrar progreso
		progress := float64(totalSent) / float64(fileSize) * 100
		if chunkNumber%10 == 0 {
			ftm.printMessage(fmt.Sprintf("Enviando... %.1f%%", progress))
		}
	}

	// Enviar último chunk vacío con IsLast=true
	finalChunk := &pb.FileChunk{
		TransferId:  transferID,
		Data:        []byte{},
		ChunkNumber: chunkNumber,
		IsLast:      true,
	}

	if err := stream.Send(finalChunk); err != nil {
		return fmt.Errorf("error al enviar chunk final: %v", err)
	}

	if err := stream.CloseSend(); err != nil {
		return fmt.Errorf("error al cerrar stream: %v", err)
	}

	ftm.printMessage(fmt.Sprintf("✅ Archivo enviado exitosamente (100%%)"))
	return nil
}

// AcceptTransfer acepta una transferencia pendiente
func (ftm *FileTransferManager) AcceptTransfer() error {
	ftm.pendingMu.Lock()
	if len(ftm.pendingRequests) == 0 {
		ftm.pendingMu.Unlock()
		ftm.printMessage("No hay solicitudes de transferencia pendientes")
		return nil
	}

	// Tomar la primera solicitud (podríamos mejorar esto para seleccionar)
	var req *pb.FileTransferRequest
	for _, r := range ftm.pendingRequests {
		req = r
		break
	}
	delete(ftm.pendingRequests, req.TransferId)
	ftm.pendingMu.Unlock()

	ftm.printMessage(fmt.Sprintf("Aceptando transferencia de '%s' desde %s...", req.Filename, req.Sender))

	// Enviar respuesta de aceptación
	resp := &pb.FileTransferResponse{
		TransferId: req.TransferId,
		Accepted:   true,
		Sender:     ftm.sender,
		Recipient:  req.Sender,
		RoomId:     ftm.roomID,
	}

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	_, err := ftm.client.RespondFileTransfer(ctx, resp)
	if err != nil {
		return fmt.Errorf("error al responder transferencia: %v", err)
	}

	// Iniciar recepción del archivo
	go ftm.streamFileReceive(req)

	return nil
}

// CancelTransfer rechaza una transferencia pendiente
func (ftm *FileTransferManager) CancelTransfer() error {
	ftm.pendingMu.Lock()
	if len(ftm.pendingRequests) == 0 {
		ftm.pendingMu.Unlock()
		ftm.printMessage("No hay solicitudes de transferencia pendientes")
		return nil
	}

	// Tomar la primera solicitud
	var req *pb.FileTransferRequest
	for _, r := range ftm.pendingRequests {
		req = r
		break
	}
	delete(ftm.pendingRequests, req.TransferId)
	ftm.pendingMu.Unlock()

	ftm.printMessage(fmt.Sprintf("Rechazando transferencia de '%s' desde %s", req.Filename, req.Sender))

	// Enviar respuesta de rechazo
	resp := &pb.FileTransferResponse{
		TransferId: req.TransferId,
		Accepted:   false,
		Sender:     ftm.sender,
		Recipient:  req.Sender,
		RoomId:     ftm.roomID,
	}

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	_, err := ftm.client.RespondFileTransfer(ctx, resp)
	if err != nil {
		return fmt.Errorf("error al responder transferencia: %v", err)
	}

	return nil
}

// streamFileReceive recibe el archivo en chunks
func (ftm *FileTransferManager) streamFileReceive(req *pb.FileTransferRequest) {
	// Crear contexto con metadatos
	ctx := context.Background()
	ctx = metadata.AppendToOutgoingContext(ctx,
		"role", "receiver",
		"transfer-id", req.TransferId,
	)

	stream, err := ftm.client.TransferFile(ctx)
	if err != nil {
		log.Printf("Error al iniciar stream de recepción: %v", err)
		return
	}

	// Crear archivo de destino
	filePath := filepath.Join(ftm.downloadDir, req.Filename)
	file, err := os.Create(filePath)
	if err != nil {
		log.Printf("Error al crear archivo: %v", err)
		return
	}
	defer file.Close()

	ftm.printMessage(fmt.Sprintf("Recibiendo '%s'...", req.Filename))

	totalReceived := int64(0)
	for {
		chunk, err := stream.Recv()
		if err == io.EOF {
			break
		}
		if err != nil {
			log.Printf("Error al recibir chunk: %v", err)
			return
		}

		if len(chunk.Data) > 0 {
			if _, err := file.Write(chunk.Data); err != nil {
				log.Printf("Error al escribir archivo: %v", err)
				return
			}
			totalReceived += int64(len(chunk.Data))
		}

		if chunk.IsLast {
			break
		}

		// Mostrar progreso
		if totalReceived > 0 && req.FileSize > 0 {
			progress := float64(totalReceived) / float64(req.FileSize) * 100
			if chunk.ChunkNumber%10 == 0 {
				ftm.printMessage(fmt.Sprintf("Recibiendo... %.1f%%", progress))
			}
		}
	}

	ftm.printMessage(fmt.Sprintf("✅ Archivo recibido: %s", filePath))
}

// NotifyRequest notifica al manager de una solicitud entrante
func (ftm *FileTransferManager) NotifyRequest(req *pb.FileTransferRequest) {
	select {
	case ftm.requestChannel <- req:
	default:
		log.Printf("Canal de solicitudes lleno, descartando solicitud")
	}
}
