package main

import (
	"bufio"
	"context"
	"flag"
	"fmt"
	"io"
	"log"
	"os"
	"strings"
	"time"

	"github.com/google/uuid"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"

	pb "go-client/chat"
)

func printHelp() {
	fmt.Println("\n═══════════════════════════════════════════════════════")
	fmt.Println("           COMANDOS DISPONIBLES")
	fmt.Println("═══════════════════════════════════════════════════════")
	fmt.Println("\n📝 Comandos de Chat:")
	fmt.Println("  /help                          - Mostrar esta ayuda")
	fmt.Println("  /quit, /exit, /disconnect      - Salir del chat")
	fmt.Println("\n🎤 Comandos de Audio:")
	fmt.Println("  /mic on                        - Activar micrófono")
	fmt.Println("  /mic off                       - Desactivar micrófono")
	fmt.Println("  /listen on                     - Activar altavoces")
	fmt.Println("  /listen off                    - Desactivar altavoces")
	fmt.Println("\n📁 Comandos de Transferencia de Archivos:")
	fmt.Println("  /upload <archivo> <usuario>    - Enviar archivo a usuario")
	fmt.Println("  /accept                        - Aceptar archivo pendiente")
	fmt.Println("  /cancel                        - Rechazar archivo pendiente")
	fmt.Println("\n💡 Ejemplos:")
	fmt.Println("  /upload /home/user/doc.pdf Juan")
	fmt.Println("  /mic on")
	fmt.Println("\n═══════════════════════════════════════════════════════\n")
}

func main() {
	// Definir flags para host y puerto
	host := flag.String("host", "", "Dirección del servidor (default: localhost)")
	port := flag.String("port", "", "Puerto del servidor (default: 50051)")
	flag.Parse()

	// Pedir valores si no se proporcionaron
	reader := bufio.NewReader(os.Stdin)

	if *host == "" {
		fmt.Print("Dirección del servidor [localhost]: ")
		hostInput, _ := reader.ReadString('\n')
		hostInput = strings.TrimSpace(hostInput)
		if hostInput == "" {
			*host = "localhost"
		} else {
			*host = hostInput
		}
	}

	if *port == "" {
		fmt.Print("Puerto del servidor [50051]: ")
		portInput, _ := reader.ReadString('\n')
		portInput = strings.TrimSpace(portInput)
		if portInput == "" {
			*port = "50051"
		} else {
			*port = portInput
		}
	}

	serverAddr := fmt.Sprintf("%s:%s", *host, *port)
	fmt.Printf("\n🔌 Conectando a %s...\n", serverAddr)

	// Conectar al servidor gRPC
	conn, err := grpc.Dial(serverAddr, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		fmt.Printf("\n❌ Error de conexión\n")
		fmt.Printf("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
		fmt.Printf("No se pudo conectar al servidor %s\n\n", serverAddr)
		fmt.Printf("Posibles causas:\n")
		fmt.Printf("  • El servidor no está ejecutándose\n")
		fmt.Printf("  • La dirección o puerto son incorrectos\n")
		fmt.Printf("  • Hay un firewall bloqueando la conexión\n")
		fmt.Printf("  • No hay conexión de red al servidor\n\n")
		fmt.Printf("Intenta:\n")
		fmt.Printf("  1. Verificar que el servidor esté corriendo: make server\n")
		fmt.Printf("  2. Verificar la dirección y puerto del servidor\n")
		fmt.Printf("  3. Usar localhost:50051 si el servidor es local\n")
		fmt.Printf("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n")
		os.Exit(1)
	}
	defer conn.Close()
	c := pb.NewChatServiceClient(conn)

	fmt.Printf("✅ Conectado al servidor exitosamente\n\n")

	var sender, roomID string
	var stream pb.ChatService_JoinChatRoomClient
	var audioStreamer *AudioStreamer
	var fileTransferManager *FileTransferManager

	// Pedir sala primero
	fmt.Println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
	fmt.Println("           UNIRSE A UNA SALA DE CHAT")
	fmt.Println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
	fmt.Print("\n🏠 ID de la sala (ej: 1, sala1, proyecto): ")
	roomID, _ = reader.ReadString('\n')
	roomID = strings.TrimSpace(roomID)

	// Loop para obtener un nombre válido
	for {
		fmt.Print("👤 Tu nombre de usuario: ")
		sender, _ = reader.ReadString('\n')
		sender = strings.TrimSpace(sender)

		if sender == "" {
			fmt.Println("El nombre no puede estar vacío. Intenta de nuevo.")
			continue
		}

		// Intentar unirse a la sala
		stream, err = c.JoinChatRoom(context.Background())
		if err != nil {
			log.Fatalf("No se pudo unir a la sala de chat: %v", err)
		}

		// Enviar el primer mensaje para unirse a la sala
		joinMsg := &pb.ChatMessage{
			Sender:    sender,
			Message:   fmt.Sprintf("%s se ha unido a la sala.", sender),
			RoomId:    roomID,
			Timestamp: time.Now().Unix(),
			TraceId:   uuid.New().String(),
		}
		if err := stream.Send(joinMsg); err != nil {
			log.Fatalf("Error al enviar mensaje para unirse: %v", err)
		}

		// Esperar respuesta para verificar si el nombre está disponible
		response, err := stream.Recv()
		if err != nil {
			log.Fatalf("Error al recibir respuesta del servidor: %v", err)
		}

		// Verificar si es un mensaje de error de nombre duplicado
		if response.Sender == "Servidor" && strings.HasPrefix(response.Message, "ERROR:NAME_TAKEN:") {
			errorMsg := strings.TrimPrefix(response.Message, "ERROR:NAME_TAKEN:")
			fmt.Printf("\n❌ %s\n", errorMsg)
			fmt.Println("Por favor, elige otro nombre.\n")
			continue
		}

		// Si no es error, el nombre está disponible
		break
	}

	fmt.Printf("✅ Conectado exitosamente como '%s' en sala '%s'\n\n", sender, roomID)

	// Crear AudioStreamer
	audioStreamer = NewAudioStreamer(c, sender, roomID)

	// Crear FileTransferManager
	fileTransferManager = NewFileTransferManager(c, sender, roomID)

	// Goroutine para recibir mensajes del servidor
	go func() {
		for {
			in, err := stream.Recv()
			if err == io.EOF {
				fmt.Println("\nConexión cerrada por el servidor.")
				os.Exit(0)
				return
			}
			if err != nil {
				log.Fatalf("Error al recibir un mensaje: %v", err)
			}
			// Imprimir mensaje recibido, si no es del mismo sender
			if in.Sender != sender {
				// Detectar mensajes especiales de transferencia de archivos
				if in.Sender == "Sistema-FileTransfer" && strings.HasPrefix(in.Message, "FILE_REQUEST:") {
					// Parsear: FILE_REQUEST:transferID:sender:filename:filesize:timestamp
					parts := strings.Split(in.Message, ":")
					if len(parts) >= 6 {
						req := &pb.FileTransferRequest{
							TransferId: parts[1],
							Sender:     parts[2],
							Recipient:  sender,
							RoomId:     roomID,
							Filename:   parts[3],
						}
						// Parsear fileSize
						if size, err := fmt.Sscanf(parts[4], "%d", &req.FileSize); err == nil && size == 1 {
							// Parsear timestamp
							if ts, err := fmt.Sscanf(parts[5], "%d", &req.Timestamp); err == nil && ts == 1 {
								fileTransferManager.NotifyRequest(req)
							}
						}
					}
				} else {
					// Limpiar línea actual, mostrar mensaje, y reimprimir prompt
					fmt.Printf("\r\x1b[2K[%s] %s: %s\n", time.Unix(in.Timestamp, 0).Format("15:04"), in.Sender, in.Message)
					fmt.Printf("[%s] Tú: ", time.Now().Format("15:04"))
				}
			}
		}
	}()

	// Bucle principal para leer la entrada del usuario y enviarla
	fmt.Println("Ya puedes chatear. Escribe tu mensaje y presiona Enter.")
	fmt.Println("Escribe /help para ver todos los comandos disponibles.")

	// Cleanup al salir
	defer func() {
		if audioStreamer.IsGrpcStreamActive() {
			audioStreamer.StopAudioConnection()
		}
	}()

	for {
		fmt.Printf("[%s] Tú: ", time.Now().Format("15:04"))
		msg, _ := reader.ReadString('\n')
		msg = strings.TrimSpace(msg)

		if msg == "/help" {
			printHelp()
			continue
		} else if msg == "/quit" || msg == "/exit" || msg == "/disconnect" {
			fmt.Println("Saliendo del chat...")
			// Send a leave message before disconnecting
			leaveMsg := &pb.ChatMessage{
				Sender:    sender,
				Message:   fmt.Sprintf("%s ha salido de la sala.", sender),
				RoomId:    roomID,
				Timestamp: time.Now().Unix(),
				TraceId:   uuid.New().String(),
			}
			if err := stream.Send(leaveMsg); err != nil {
				log.Printf("Error al enviar mensaje de salida: %v", err)
			}
			// Close the stream to notify the server
			stream.CloseSend()
			break
		} else if msg == "/mic on" {
			if !audioStreamer.IsGrpcStreamActive() {
				audioStreamer.StartAudioConnection()
			}
			audioStreamer.StartSpeakers()
			audioStreamer.StartMic()
			continue
		} else if msg == "/mic off" {
			audioStreamer.StopMic()
			audioStreamer.StopSpeakers()
			if !audioStreamer.IsMicActive() && !audioStreamer.IsSpeakersActive() {
				audioStreamer.StopAudioConnection()
			}
			continue
		} else if msg == "/listen on" {
			if !audioStreamer.IsGrpcStreamActive() {
				audioStreamer.StartAudioConnection()
			}
			audioStreamer.StartSpeakers()
			continue
		} else if msg == "/listen off" {
			audioStreamer.StopSpeakers()
			if !audioStreamer.IsMicActive() && !audioStreamer.IsSpeakersActive() {
				audioStreamer.StopAudioConnection()
			}
			continue
		} else if strings.HasPrefix(msg, "/upload ") {
			parts := strings.Fields(msg)
			if len(parts) != 3 {
				fmt.Println("Uso: /upload <archivo> <destinatario>")
				continue
			}
			filePath := parts[1]
			recipient := parts[2]
			go func() {
				if err := fileTransferManager.SendFile(filePath, recipient); err != nil {
					log.Printf("Error al enviar archivo: %v", err)
				}
			}()
			continue
		} else if msg == "/accept" {
			go func() {
				if err := fileTransferManager.AcceptTransfer(); err != nil {
					log.Printf("Error al aceptar transferencia: %v", err)
				}
			}()
			continue
		} else if msg == "/cancel" {
			go func() {
				if err := fileTransferManager.CancelTransfer(); err != nil {
					log.Printf("Error al cancelar transferencia: %v", err)
				}
			}()
			continue
		}

		if msg != "" {
			chatMsg := &pb.ChatMessage{
				Sender:    sender,
				Message:   msg,
				RoomId:    roomID,
				Timestamp: time.Now().Unix(),
				TraceId:   uuid.New().String(),
			}
			if err := stream.Send(chatMsg); err != nil {
				log.Printf("Error al enviar el mensaje: %v", err)
			}
		}
	}
}
