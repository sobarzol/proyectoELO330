//go:build windows
// +build windows

package main

import (
	"context"
	"fmt"
	"io"
	"log"
	"time"

	"github.com/gordonklaus/portaudio"
	"google.golang.org/grpc/metadata"

	pb "go-client/chat"
)

// AudioStreamer maneja el streaming de audio bidireccional
type AudioStreamer struct {
	client         pb.ChatServiceClient
	sender         string
	roomID         string
	grpcStreamActive bool
	micActive      bool
	speakersActive bool
	inputStream    *portaudio.Stream
	outputStream   *portaudio.Stream
	audioStream    pb.ChatService_StreamAudioClient
	stopChan       chan bool
}

const (
	sampleRate  = 44100
	channels    = 1
	framesPerBuffer = 1024
)

// NewAudioStreamer crea un nuevo AudioStreamer
func NewAudioStreamer(client pb.ChatServiceClient, sender, roomID string) *AudioStreamer {
	return &AudioStreamer{
		client:         client,
		sender:         sender,
		roomID:         roomID,
		grpcStreamActive: false,
		micActive:      false,
		speakersActive: false,
		stopChan:       make(chan bool),
	}
}

func (a *AudioStreamer) printMessage(message string) {
	fmt.Printf("\r\x1b[2K%s\n[%s] Tú: ", message, time.Now().Format("15:04"))
}

// StartAudioConnection establece la conexión bidireccional gRPC para audio
func (a *AudioStreamer) StartAudioConnection() error {
	if a.grpcStreamActive {
		a.printMessage("La conexión de audio gRPC ya está activa.")
		return nil
	}

	// Crear metadatos
	md := metadata.New(map[string]string{
		"sender":  a.sender,
		"room-id": a.roomID,
	})
	ctx := metadata.NewOutgoingContext(context.Background(), md)

	// Iniciar stream bidireccional
	stream, err := a.client.StreamAudio(ctx)
	if err != nil {
		return fmt.Errorf("error al establecer conexión de audio: %v", err)
	}

	a.audioStream = stream
	a.grpcStreamActive = true

	// Goroutine para recibir audio
	go a.receiveAudio()

	a.printMessage("Conexión de audio gRPC establecida.")
	return nil
}

// StopAudioConnection cierra la conexión bidireccional gRPC para audio
func (a *AudioStreamer) StopAudioConnection() {
	if !a.grpcStreamActive {
		return
	}

	a.grpcStreamActive = false
	a.StopMic()
	a.StopSpeakers()

	if a.audioStream != nil {
		a.audioStream.CloseSend()
	}

	a.printMessage("Conexión de audio gRPC cerrada.")
}

// StartMic inicia la captura de audio del micrófono
func (a *AudioStreamer) StartMic() error {
	if a.micActive {
		a.printMessage("Micrófono ya activo.")
		return nil
	}

	if !a.grpcStreamActive {
		log.Println("Primero debes establecer la conexión gRPC de audio (/mic on o /listen on).")
		return fmt.Errorf("conexión gRPC no activa")
	}

	// Inicializar PortAudio
	if err := portaudio.Initialize(); err != nil {
		return fmt.Errorf("error al inicializar PortAudio: %v", err)
	}

	// Abrir stream de entrada
	inputBuffer := make([]int16, framesPerBuffer)
	stream, err := portaudio.OpenDefaultStream(channels, 0, sampleRate, framesPerBuffer, inputBuffer)
	if err != nil {
		portaudio.Terminate()
		return fmt.Errorf("error al abrir micrófono: %v", err)
	}

	if err := stream.Start(); err != nil {
		stream.Close()
		portaudio.Terminate()
		return fmt.Errorf("error al iniciar micrófono: %v", err)
	}

	a.inputStream = stream
	a.micActive = true

	// Goroutine para capturar y enviar audio
	go func() {
		defer func() {
			if r := recover(); r != nil {
				log.Printf("Panic en captura de audio: %v\n", r)
				a.micActive = false
			}
		}()

		for a.micActive && a.audioStream != nil {
			// Leer del stream (los datos van a inputBuffer)
			if err := a.inputStream.Read(); err != nil {
				// Solo logear si no es un cierre normal
				if a.micActive {
					log.Printf("Error al leer del micrófono: %v\n", err)
				}
				a.micActive = false
				break
			}

			// Convertir int16 a bytes
			byteBuffer := make([]byte, len(inputBuffer)*2)
			for i, sample := range inputBuffer {
				byteBuffer[i*2] = byte(sample)
				byteBuffer[i*2+1] = byte(sample >> 8)
			}

			// Enviar al servidor
			chunk := &pb.AudioChunk{Data: byteBuffer}
			if err := a.audioStream.Send(chunk); err != nil {
				if a.micActive {
					log.Printf("Error al enviar audio: %v\n", err)
				}
				a.micActive = false
				break
			}
		}

		if a.micActive {
			a.printMessage("Captura de micrófono detenida.")
		}
	}()

	a.printMessage("Micrófono activado. Transmitiendo voz...")
	return nil
}

// StopMic detiene la captura de audio del micrófono
func (a *AudioStreamer) StopMic() {
	if !a.micActive {
		return
	}

	a.micActive = false

	if a.inputStream != nil {
		a.inputStream.Stop()
		a.inputStream.Close()
		portaudio.Terminate()
		a.inputStream = nil
	}

	a.printMessage("Micrófono detenido.")
}

// StartSpeakers inicia la reproducción de audio
func (a *AudioStreamer) StartSpeakers() error {
	if a.speakersActive {
		a.printMessage("Altavoces ya activos para reproducción.")
		return nil
	}

	if !a.grpcStreamActive {
		log.Println("Primero debes establecer la conexión gRPC de audio (/mic on o /listen on).")
		return fmt.Errorf("conexión gRPC no activa")
	}

	// Inicializar PortAudio si no está inicializado
	if err := portaudio.Initialize(); err != nil {
		return fmt.Errorf("error al inicializar PortAudio: %v", err)
	}

	// Abrir stream de salida
	outputBuffer := make([]int16, framesPerBuffer)
	stream, err := portaudio.OpenDefaultStream(0, channels, sampleRate, framesPerBuffer, outputBuffer)
	if err != nil {
		portaudio.Terminate()
		return fmt.Errorf("error al abrir altavoces: %v", err)
	}

	if err := stream.Start(); err != nil {
		stream.Close()
		portaudio.Terminate()
		return fmt.Errorf("error al iniciar altavoces: %v", err)
	}

	a.outputStream = stream
	a.speakersActive = true

	a.printMessage("Altavoces activados para reproducción de audio.")
	return nil
}

// StopSpeakers detiene la reproducción de audio
func (a *AudioStreamer) StopSpeakers() {
	if !a.speakersActive {
		return
	}

	a.speakersActive = false

	if a.outputStream != nil {
		a.outputStream.Stop()
		a.outputStream.Close()
		portaudio.Terminate()
		a.outputStream = nil
	}

	a.printMessage("Altavoces detenidos.")
}

// receiveAudio recibe audio del servidor y lo reproduce
func (a *AudioStreamer) receiveAudio() {
	outputBuffer := make([]int16, framesPerBuffer)

	for a.grpcStreamActive {
		chunk, err := a.audioStream.Recv()
		if err == io.EOF {
			a.printMessage("Recepción de audio gRPC finalizada (EOF).")
			break
		}
		if err != nil {
			if a.grpcStreamActive {
				log.Printf("Error al recibir audio: %v\n", err)
			}
			break
		}

		if a.speakersActive && a.outputStream != nil {
			// Convertir bytes a int16
			data := chunk.GetData()
			for i := 0; i < len(data)/2 && i < len(outputBuffer); i++ {
				outputBuffer[i] = int16(data[i*2]) | int16(data[i*2+1])<<8
			}

			// Escribir a los altavoces
			if err := a.outputStream.Write(); err != nil {
				log.Printf("Error al reproducir audio: %v\n", err)
			}
		}
	}
}

// IsMicActive retorna si el micrófono está activo
func (a *AudioStreamer) IsMicActive() bool {
	return a.micActive
}

// IsSpeakersActive retorna si los altavoces están activos
func (a *AudioStreamer) IsSpeakersActive() bool {
	return a.speakersActive
}

// IsGrpcStreamActive retorna si el stream gRPC está activo
func (a *AudioStreamer) IsGrpcStreamActive() bool {
	return a.grpcStreamActive
}
