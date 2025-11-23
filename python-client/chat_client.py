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

    def generate_messages(self, first_message_sent):
        """
        Generador que produce mensajes del usuario
        """
        # Primer mensaje: unirse a la sala (solo si no se ha enviado antes)
        if not first_message_sent[0]:
            join_msg = chat_pb2.ChatMessage(
                sender=self.sender,
                message=f"{self.sender} se ha unido a la sala.",
                room_id=self.room_id,
                timestamp=int(time.time()),
                trace_id=str(uuid.uuid4())
            )
            first_message_sent[0] = True
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
    # Pedir dirección del servidor
    server_addr = input("Dirección del servidor [localhost]: ").strip()
    if not server_addr:
        server_addr = "localhost"
    
    server_port = input("Puerto del servidor [50051]: ").strip()
    if not server_port:
        server_port = "50051"
    
    server = f"{server_addr}:{server_port}"
    print(f"\n🔌 Conectando a {server}...")
    
    # Conectar al servidor
    try:
        channel = grpc.insecure_channel(server)
        stub = chat_pb2_grpc.ChatServiceStub(channel)
        print("✅ Conectado al servidor exitosamente\n")
    except Exception as e:
        print(f"❌ Error al conectar: {e}")
        return
    
    # Prompts mejorados
    print("━" * 50)
    print("           UNIRSE A UNA SALA DE CHAT")
    print("━" * 50)
    
    room_id = input("\n🏠 ID de la sala (ej: 1, sala1, proyecto): ").strip()
    
    if not room_id:
        print("¡El ID de la sala no puede estar vacío!")
        channel.close()
        return
    
    # Loop para obtener un nombre válido
    while True:
        sender = input("👤 Tu nombre de usuario: ").strip()
        
        if not sender:
            print("El nombre no puede estar vacío. Intenta de nuevo.")
            continue
        
        # Intentar unirse a la sala
        first_message_sent = [False]
        chat_client = ChatClient(stub, sender, room_id)
        
        try:
            # Iniciar el stream bidireccional
            response_iterator = stub.JoinChatRoom(chat_client.generate_messages(first_message_sent))
            
            # Esperar la primera respuesta para verificar si el nombre está disponible
            first_response = next(response_iterator)
            
            # Verificar si es un mensaje de error de nombre duplicado
            if first_response.sender == "Servidor" and first_response.message.startswith("ERROR:NAME_TAKEN:"):
                error_msg = first_response.message.replace("ERROR:NAME_TAKEN:", "")
                print(f"\n❌ {error_msg}")
                print("Por favor, elige otro nombre.\n")
                continue
            
            # Si no es error, el nombre está disponible
            print(f"✅ Conectado exitosamente como '{sender}' en sala '{room_id}'\n")
            break
            
        except grpc.RpcError as e:
            print(f"Error al conectar con el servidor: {e}")
            channel.close()
            return

    try:
        # Iniciar hilo para recibir mensajes (usando el response_iterator existente)
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
