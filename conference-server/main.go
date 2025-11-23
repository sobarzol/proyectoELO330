package main

import (
	"context"
	"fmt"
	"io"
	"log"
	"net"
	"sync"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/peer"
	"google.golang.org/grpc/status"

	pb "conference-server/conference"
)

// --- Structs for managing state ---

type Client struct {
	id     string // sender ID / username
	addr   string
	ch     chan *pb.ConferenceData
	stream pb.ConferenceService_JoinConferenceServer
}

type Room struct {
	id      string
	clients *sync.Map // map[clientAddr]*Client
	users   *sync.Map // map[senderID]*Client
}

func NewRoom(id string) *Room {
	return &Room{
		id:      id,
		clients: &sync.Map{},
		users:   &sync.Map{},
	}
}

// AddClient adds a client to the room, checking for username uniqueness.
func (r *Room) AddClient(c *Client) error {
	// Check if username is already taken
	if _, ok := r.users.Load(c.id); ok {
		return fmt.Errorf("username '%s' is already taken", c.id)
	}
	r.clients.Store(c.addr, c)
	r.users.Store(c.id, c)
	return nil
}

// RemoveClient removes a client from the room.
func (r *Room) RemoveClient(c *Client) {
	r.clients.Delete(c.addr)
	r.users.Delete(c.id)
}

// server implements the conference.ConferenceServiceServer interface.
type server struct {
	pb.UnimplementedConferenceServiceServer
	rooms sync.Map // map[roomID]*Room

	// File transfer state
	transferResponses map[string]chan *pb.FileTransferResponse
	transferMu        sync.Mutex
	activeTransfers   sync.Map // map[transferID]transfer (p2pTransfer or broadcastTransfer)
}

func newServer() *server {
	return &server{
		transferResponses: make(map[string]chan *pb.FileTransferResponse),
	}
}

// --- JoinConference: Main communication stream ---

func (s *server) JoinConference(stream pb.ConferenceService_JoinConferenceServer) error {
	p, _ := peer.FromContext(stream.Context())
	clientAddr := p.Addr.String()

	initialMsg, err := stream.Recv()
	if err != nil {
		return status.Errorf(codes.InvalidArgument, "Failed to receive initial message: %v", err)
	}
	roomID := initialMsg.GetRoomId()
	senderID := initialMsg.GetSender()
	if roomID == "" || senderID == "" {
		return status.Errorf(codes.InvalidArgument, "room_id and sender must be provided")
	}

	// Get or create room
	r, _ := s.rooms.LoadOrStore(roomID, NewRoom(roomID))
	room := r.(*Room)

	// Create and add client
	client := &Client{
		id:     senderID,
		addr:   clientAddr,
		ch:     make(chan *pb.ConferenceData, 100),
		stream: stream,
	}
	if err := room.AddClient(client); err != nil {
		log.Printf("Client '%s' failed to join room '%s': %v", senderID, roomID, err)
		// Send error back to client before closing
		stream.Send(&pb.ConferenceData{
			Payload: &pb.ConferenceData_Command{Command: &pb.Command{Type: "ERROR", Value: err.Error()}},
		})
		return status.Error(codes.AlreadyExists, err.Error())
	}
	log.Printf("Client '%s' (%s) joined room '%s'", senderID, clientAddr, roomID)

	defer func() {
		room.RemoveClient(client)
		close(client.ch)
		log.Printf("Client '%s' left room '%s'", senderID, roomID)
		if room.IsEmpty() {
			s.rooms.Delete(roomID)
			log.Printf("Room '%s' is empty and deleted.", roomID)
		} else {
			room.Broadcast(&pb.ConferenceData{
				Sender: "Server", RoomId: roomID,
				Payload: &pb.ConferenceData_Command{Command: &pb.Command{Type: "USER_LEFT", Value: senderID}},
			}, "")
		}
	}()
	
	// Announce new user
	room.Broadcast(&pb.ConferenceData{
		Sender: "Server", RoomId: roomID,
		Payload: &pb.ConferenceData_Command{Command: &pb.Command{Type: "USER_JOINED", Value: senderID}},
	}, "")
	
	// Welcome message to the user
	client.ch <- &pb.ConferenceData{
		Payload: &pb.ConferenceData_Command{Command: &pb.Command{Type: "WELCOME", Value: fmt.Sprintf("Welcome to room '%s'", roomID)}},
	}

	// Goroutine to send messages from channel to the client's stream
	go func() {
		for msg := range client.ch {
			if err := client.stream.Send(msg); err != nil {
				log.Printf("Error sending to client %s: %v. Closing channel.", client.id, err)
				// The main loop will detect the stream error and clean up.
				return
			}
		}
	}()

	// Main loop to process incoming messages from this client
	for {
		msg, err := stream.Recv()
		if err == io.EOF { return nil }
		if err != nil { return err }

		switch payload := msg.Payload.(type) {
		case *pb.ConferenceData_PrivateMessage:
			s.handlePrivateMessage(room, client, payload.PrivateMessage)
		case *pb.ConferenceData_FileAnnouncement:
			log.Printf("File announcement from '%s' in room '%s' for '%s'", msg.Sender, msg.RoomId, payload.FileAnnouncement.Filename)
			s.activeTransfers.Store(payload.FileAnnouncement.TransferId, &broadcastTransfer{})
			room.Broadcast(msg, client.addr)
		default:
			room.Broadcast(msg, client.addr)
		}
	}
}

// --- Message Handling ---

func (r *Room) Broadcast(msg *pb.ConferenceData, senderAddr string) {
	r.clients.Range(func(key, value interface{}) bool {
		clientAddr := key.(string)
		if clientAddr == senderAddr { // Don't send back to sender
			return true
		}
		client := value.(*Client)
		select {
		case client.ch <- msg:
		default:
			log.Printf("Dropped message for client %s, channel full.", client.id)
		}
		return true
	})
}

func (s *server) handlePrivateMessage(room *Room, sender *Client, pm *pb.PrivateMessage) {
	recipientID := pm.RecipientId
	if val, ok := room.users.Load(recipientID); ok {
		recipient := val.(*Client)
		
		// Format the private message as a standard ChatMessage for the recipient
		privateContent := fmt.Sprintf("(private from %s) %s", sender.id, pm.Content)
		fwdMsg := &pb.ConferenceData{
			RoomId: room.id,
			Sender: sender.id,
			Payload: &pb.ConferenceData_TextMessage{
				TextMessage: &pb.ChatMessage{
					Sender: sender.id,
					Content: privateContent,
					RoomId: room.id,
					Timestamp: time.Now().Unix(),
				},
			},
		}
		recipient.ch <- fwdMsg
		log.Printf("Relayed private message from '%s' to '%s'", sender.id, recipient.id)
	} else {
		// Send "user not found" error back to the sender
		notFoundMsg := &pb.ConferenceData{
			Sender: "Server",
			Payload: &pb.ConferenceData_Command{
				Command: &pb.Command{Type: "ERROR", Value: fmt.Sprintf("User '%s' not found in this room.", recipientID)},
			},
		}
		sender.ch <- notFoundMsg
		log.Printf("Failed to send private message from '%s': user '%s' not found.", sender.id, recipientID)
	}
}

// --- Room Helpers ---
func (r *Room) IsEmpty() bool {
	count := 0
	r.clients.Range(func(_, _ interface{}) bool {
		count++
		return false // Stop after finding one
	})
	return count == 0
}


// --- File Transfer (Unchanged from previous step, but placed here for completeness) ---

type transfer interface { isTransfer() }
type p2pTransfer struct { sender pb.ConferenceService_TransferFileServer; receiver pb.ConferenceService_TransferFileServer; mu sync.Mutex }
func (t *p2pTransfer) isTransfer() {}
type broadcastTransfer struct { sender pb.ConferenceService_TransferFileServer; receivers sync.Map; mu sync.Mutex }
func (t *broadcastTransfer) isTransfer() {}

func (s *server) RequestFileTransfer(ctx context.Context, req *pb.FileTransferRequest) (*pb.FileTransferResponse, error) {
	log.Printf("P2P file request from '%s' to '%s' for file '%s'", req.Sender, req.Recipient, req.Filename)
	respChan := make(chan *pb.FileTransferResponse, 1)
	s.transferMu.Lock()
	s.transferResponses[req.TransferId] = respChan
	s.transferMu.Unlock()
	defer func() { s.transferMu.Lock(); delete(s.transferResponses, req.TransferId); s.transferMu.Unlock() }()
	notificationMsg := &pb.ConferenceData{
		RoomId: req.RoomId, Sender: "Sistema-FileTransfer",
		Payload: &pb.ConferenceData_TextMessage{ TextMessage: &pb.ChatMessage{ Content: fmt.Sprintf("FILE_REQUEST:%s:%s:%s:%d:%d", req.TransferId, req.Sender, req.Filename, req.FileSize, req.Timestamp) } },
	}
	if r, ok := s.rooms.Load(req.RoomId); ok { r.(*Room).Broadcast(notificationMsg, "") }
	select {
	case resp := <-respChan:
		if resp.Accepted { s.activeTransfers.Store(req.TransferId, &p2pTransfer{}) }
		return resp, nil
	case <-time.After(60 * time.Second):
		return &pb.FileTransferResponse{TransferId: req.TransferId, Accepted: false}, nil
	}
}
func (s *server) RespondFileTransfer(ctx context.Context, resp *pb.FileTransferResponse) (*pb.FileTransferResponse, error) {
	s.transferMu.Lock()
	respChan, ok := s.transferResponses[resp.TransferId]
	s.transferMu.Unlock()
	if !ok { return nil, fmt.Errorf("invalid transfer ID") }
	respChan <- resp
	return resp, nil
}
func (s *server) TransferFile(stream pb.ConferenceService_TransferFileServer) error {
	md, _ := metadata.FromIncomingContext(stream.Context())
	tID := md.Get("transfer-id")[0]; role := md.Get("role")[0]
	p, _ := peer.FromContext(stream.Context()); clientAddr := p.Addr.String()
	val, ok := s.activeTransfers.Load(tID)
	if !ok { return fmt.Errorf("transfer not initiated") }
	switch tx := val.(type) {
	case *p2pTransfer: return s.handleP2PTransfer(tx, stream, role, tID)
	case *broadcastTransfer: return s.handleBroadcastTransfer(tx, stream, role, clientAddr, tID)
	default: return fmt.Errorf("unknown transfer type")
	}
}
func (s *server) handleP2PTransfer(tx *p2pTransfer, stream pb.ConferenceService_TransferFileServer, role, tID string) error {
	if role == "sender" {
		tx.mu.Lock(); tx.sender = stream; receiver := tx.receiver; tx.mu.Unlock()
		if receiver != nil { go s.proxyP2PChunks(tx.sender, receiver, tID) }
	} else if role == "receiver" {
		tx.mu.Lock(); tx.receiver = stream; sender := tx.sender; tx.mu.Unlock()
		if sender != nil { go s.proxyP2PChunks(sender, tx.receiver, tID) }
	}
	<-stream.Context().Done()
	return nil
}
func (s *server) handleBroadcastTransfer(tx *broadcastTransfer, stream pb.ConferenceService_TransferFileServer, role, clientAddr, tID string) error {
	if role == "sender" {
		tx.mu.Lock()
		if tx.sender != nil { tx.mu.Unlock(); return fmt.Errorf("broadcast sender for '%s' already exists", tID) }
		tx.sender = stream
		tx.mu.Unlock()
		s.proxyBroadcastChunks(tx, tID)
	} else if role == "receiver" {
		tx.receivers.Store(clientAddr, stream)
		defer tx.receivers.Delete(clientAddr)
	}
	<-stream.Context().Done()
	return nil
}
func (s *server) proxyP2PChunks(sender pb.ConferenceService_TransferFileServer, receiver pb.ConferenceService_TransferFileServer, tID string) {
	for {
		chunk, err := sender.Recv()
		if err != nil { return }
		if err := receiver.Send(chunk); err != nil { return }
	}
}
func (s *server) proxyBroadcastChunks(tx *broadcastTransfer, tID string) {
	defer s.activeTransfers.Delete(tID)
	for {
		chunk, err := tx.sender.Recv()
		if err != nil { return }
		tx.receivers.Range(func(key, value interface{}) bool {
			receiverStream := value.(pb.ConferenceService_TransferFileServer)
			if err := receiverStream.Send(chunk); err != nil { tx.receivers.Delete(key) }
			return true
		})
		if chunk.GetIsLast() { return }
	}
}

// --- Main ---
func main() {
	lis, err := net.Listen("tcp", ":50051")
	if err != nil { log.Fatalf("Failed to listen: %v", err) }
	s := grpc.NewServer()
	pb.RegisterConferenceServiceServer(s, newServer())
	log.Printf("Server listening at %v", lis.Addr())
	if err := s.Serve(lis); err != nil { log.Fatalf("Failed to serve: %v", err) }
}
