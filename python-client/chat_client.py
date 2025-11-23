#!/usr/bin/env python3
"""
Cliente de chat gRPC en Python
"""
import grpc
import sys
import time
import threading
import uuid
from proto import chat_pb2
from proto import chat_pb2_grpc
from audio_streamer import AudioStreamer


class ChatClient:
    """
    Cliente de chat con soporte de audio
    """
    def __init__(self, stub, sender, room_id):
        self.stub = stub
        self.sender = sender
        self.room_id = room_id
        self.audio_streamer = AudioStreamer(stub, sender, room_id)
        self.running = True

    def generate_messages(self):
        """
        Generador que produce mensajes del usuario
        """
        # Primer mensaje: unirse a la sala
        join_msg = chat_pb2.ChatMessage(
            sender=self.sender,
            message=f"{self.sender} se ha unido a la sala.",
            room_id=self.room_id,
            timestamp=int(time.time()),
            trace_id=str(uuid.uuid4())
        )
        yield join_msg

        # Bucle para leer mensajes del usuario
        print("Ya puedes chatear. Escribe tu mensaje y presiona Enter.")
        print("Usa /mic on para enviar tu voz, /mic off para silenciarte.")
        print("Usa /listen on para escuchar, /listen off para dejar de escuchar.")
        print("El audio se activará automáticamente con /mic on o /listen on.")

        while self.running:
            try:
                msg = input(f"[{time.strftime('%H:%M')}] Tú: ")
                trimmed_msg = msg.strip()

                if trimmed_msg.lower() in ["/quit", "/exit", "/disconnect"]:
                    print("\nSaliendo del chat...")
                    leave_msg = chat_pb2.ChatMessage(
                        sender=self.sender,
                        message=f"{self.sender} ha salido de la sala.",
                        room_id=self.room_id,
                        timestamp=int(time.time()),
                        trace_id=str(uuid.uuid4())
                    )
                    yield leave_msg
                    self.running = False
                    break

                elif trimmed_msg.lower() == "/mic on":
                    if not self.audio_streamer.is_grpc_stream_active():
                        self.audio_streamer.start_audio_connection()
                    self.audio_streamer.start_speakers()
                    self.audio_streamer.start_mic()
                    continue

                elif trimmed_msg.lower() == "/mic off":
                    self.audio_streamer.stop_mic()
                    self.audio_streamer.stop_speakers()
                    if not self.audio_streamer.is_mic_active() and not self.audio_streamer.is_speakers_active():
                        self.audio_streamer.stop_audio_connection()
                    continue

                elif trimmed_msg.lower() == "/listen on":
                    if not self.audio_streamer.is_grpc_stream_active():
                        self.audio_streamer.start_audio_connection()
                    self.audio_streamer.start_speakers()
                    continue

                elif trimmed_msg.lower() == "/listen off":
                    self.audio_streamer.stop_speakers()
                    if not self.audio_streamer.is_mic_active() and not self.audio_streamer.is_speakers_active():
                        self.audio_streamer.stop_audio_connection()
                    continue

                if trimmed_msg:
                    chat_msg = chat_pb2.ChatMessage(
                        sender=self.sender,
                        message=trimmed_msg,
                        room_id=self.room_id,
                        timestamp=int(time.time()),
                        trace_id=str(uuid.uuid4())
                    )
                    yield chat_msg
            except (EOFError, KeyboardInterrupt):
                print("\nSaliendo del chat...")
                self.running = False
                break


def receive_messages(response_iterator, chat_client):
    """
    Función que recibe mensajes del servidor en un hilo separado
    """
    try:
        for msg in response_iterator:
            # Solo mostrar mensajes de otros usuarios
            if msg.sender != chat_client.sender:
                timestamp = time.strftime('%H:%M', time.localtime(msg.timestamp))
                # Limpiar la línea actual y reimprimir el prompt para una UI más limpia
                sys.stdout.write(f"\r\x1b[2K[TraceID: {msg.trace_id}]\n")
                sys.stdout.write(f"[{timestamp}] {msg.sender}: {msg.message}\n")
                sys.stdout.write(f"[{time.strftime('%H:%M')}] Tú: ")
                sys.stdout.flush()
    except grpc.RpcError as e:
        # Errores de RPC son esperados al cerrar el cliente, no mostrar si es 'CANCELLED'
        if e.code() not in (grpc.StatusCode.CANCELLED, grpc.StatusCode.UNAVAILABLE):
            print(f"\nError de conexión: {e.details()}")


def run():
    """
    Función principal del cliente
    """
    # Pedir datos del usuario
    sender = input("Ingresa tu nombre: ").strip()
    room_id = input("Ingresa el ID de la sala: ").strip()

    if not sender or not room_id:
        print("¡El nombre y el ID de la sala no pueden estar vacíos!")
        return

    # Conectar al servidor
    channel = grpc.insecure_channel('localhost:50051')
    stub = chat_pb2_grpc.ChatServiceStub(channel)

    # Crear cliente de chat
    chat_client = ChatClient(stub, sender, room_id)

    try:
        # Iniciar el stream bidireccional
        response_iterator = stub.JoinChatRoom(chat_client.generate_messages())

        # Iniciar hilo para recibir mensajes
        receive_thread = threading.Thread(
            target=receive_messages,
            args=(response_iterator, chat_client),
            daemon=True
        )
        receive_thread.start()

        # Esperar a que el hilo termine
        receive_thread.join()

    except grpc.RpcError as e:
        print(f"Error al conectar con el servidor: {e}")
    except KeyboardInterrupt:
        print("\nSaliendo...")
    finally:
        # Limpiar recursos de audio
        chat_client.audio_streamer.cleanup()
        channel.close()


if __name__ == '__main__':
    run()
