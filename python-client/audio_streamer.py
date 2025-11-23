#!/usr/bin/env python3
"""
Módulo para streaming de audio bidireccional
"""
import grpc
import pyaudio
import threading
import time
from proto import chat_pb2


class AudioStreamer:
    """
    Maneja el streaming de audio bidireccional con el servidor gRPC
    """

    # Configuración de audio
    AUDIO_FORMAT = pyaudio.paInt16
    CHANNELS = 1
    RATE = 44100
    CHUNK = 1024

    def __init__(self, stub, sender, room_id):
        """
        Inicializa el AudioStreamer

        Args:
            stub: Cliente gRPC
            sender: Nombre del usuario
            room_id: ID de la sala
        """
        self.stub = stub
        self.sender = sender
        self.room_id = room_id

        # Audio components
        self.audio = pyaudio.PyAudio()
        self.microphone_stream = None
        self.speakers_stream = None

        # Control de estados
        self.grpc_stream_active = False
        self.mic_active = False
        self.speakers_active = False

        # Thread y observer para gRPC
        self.request_observer = None
        self.mic_thread = None
        self.receive_thread = None
        self.response_iterator = None

    def _print_message(self, message):
        """
        Imprime un mensaje y redibuja el prompt
        """
        ansi_clear_line = "\r\x1b[2K"
        prompt = f"[{time.strftime('%H:%M')}] Tú: "
        print(f"{ansi_clear_line}{message}\n{prompt}", end="", flush=True)

    def start_audio_connection(self):
        """
        Establece la conexión bidireccional gRPC para audio
        """
        if self.grpc_stream_active:
            self._print_message("La conexión de audio gRPC ya está activa.")
            return

        # Crear metadatos para enviar sender y room_id
        metadata = (
            ('sender', self.sender),
            ('room-id', self.room_id),
        )

        try:
            # Iniciar el stream bidireccional con metadatos
            self.response_iterator = self.stub.StreamAudio(
                self._generate_audio_chunks(),
                metadata=metadata
            )

            self.grpc_stream_active = True

            # Iniciar thread para recibir audio
            self.receive_thread = threading.Thread(
                target=self._receive_audio,
                daemon=True
            )
            self.receive_thread.start()

            self._print_message("Conexión de audio gRPC establecida.")

        except grpc.RpcError as e:
            print(f"\nError al establecer conexión de audio: {e.details()}")
            self.grpc_stream_active = False

    def _generate_audio_chunks(self):
        """
        Generador que produce chunks de audio del micrófono
        """
        while self.grpc_stream_active:
            if self.mic_active and self.microphone_stream:
                try:
                    data = self.microphone_stream.read(self.CHUNK, exception_on_overflow=False)
                    yield chat_pb2.AudioChunk(data=data)
                except Exception as e:
                    print(f"\nError al capturar audio: {e}")
                    break
            else:
                # Si el mic no está activo, esperar un poco
                time.sleep(0.1)

    def _receive_audio(self):
        """
        Recibe audio del servidor y lo reproduce
        """
        try:
            for audio_chunk in self.response_iterator:
                if self.speakers_active and self.speakers_stream:
                    self.speakers_stream.write(audio_chunk.data)
        except grpc.RpcError as e:
            if e.code() not in (grpc.StatusCode.CANCELLED, grpc.StatusCode.UNAVAILABLE):
                print(f"\nError en el stream de audio gRPC: {e.details()}")
        except Exception as e:
            print(f"\nError al recibir audio: {e}")
        finally:
            self._print_message("Recepción de audio gRPC finalizada.")

    def stop_audio_connection(self):
        """
        Cierra la conexión bidireccional gRPC para audio
        """
        if not self.grpc_stream_active:
            return

        self.grpc_stream_active = False
        self.stop_mic()
        self.stop_speakers()

        # Esperar a que los threads terminen
        if self.receive_thread and self.receive_thread.is_alive():
            self.receive_thread.join(timeout=1)

        self._print_message("Conexión de audio gRPC cerrada.")

    def start_mic(self):
        """
        Inicia la captura de audio del micrófono
        """
        if self.mic_active:
            self._print_message("Micrófono ya activo.")
            return

        if not self.grpc_stream_active:
            print("\nPrimero debes establecer la conexión gRPC de audio (/mic on o /listen on).")
            return

        try:
            self.microphone_stream = self.audio.open(
                format=self.AUDIO_FORMAT,
                channels=self.CHANNELS,
                rate=self.RATE,
                input=True,
                frames_per_buffer=self.CHUNK
            )
            self.mic_active = True
            self._print_message("Micrófono activado. Transmitiendo voz...")

        except Exception as e:
            print(f"\nError al acceder al micrófono: {e}")
            self.mic_active = False

    def stop_mic(self):
        """
        Detiene la captura de audio del micrófono
        """
        if not self.mic_active:
            return

        self.mic_active = False

        if self.microphone_stream:
            self.microphone_stream.stop_stream()
            self.microphone_stream.close()
            self.microphone_stream = None

        self._print_message("Micrófono detenido.")

    def start_speakers(self):
        """
        Inicia la reproducción de audio por los altavoces
        """
        if self.speakers_active:
            self._print_message("Altavoces ya activos para reproducción.")
            return

        if not self.grpc_stream_active:
            print("\nPrimero debes establecer la conexión gRPC de audio (/mic on o /listen on).")
            return

        try:
            self.speakers_stream = self.audio.open(
                format=self.AUDIO_FORMAT,
                channels=self.CHANNELS,
                rate=self.RATE,
                output=True,
                frames_per_buffer=self.CHUNK
            )
            self.speakers_active = True
            self._print_message("Altavoces activados para reproducción de audio.")

        except Exception as e:
            print(f"\nError al acceder a los altavoces: {e}")
            self.speakers_active = False

    def stop_speakers(self):
        """
        Detiene la reproducción de audio por los altavoces
        """
        if not self.speakers_active:
            return

        self.speakers_active = False

        if self.speakers_stream:
            self.speakers_stream.stop_stream()
            self.speakers_stream.close()
            self.speakers_stream = None

        self._print_message("Altavoces detenidos.")

    def is_mic_active(self):
        """Retorna si el micrófono está activo"""
        return self.mic_active

    def is_speakers_active(self):
        """Retorna si los altavoces están activos"""
        return self.speakers_active

    def is_grpc_stream_active(self):
        """Retorna si el stream gRPC está activo"""
        return self.grpc_stream_active

    def cleanup(self):
        """
        Limpia recursos de audio
        """
        self.stop_audio_connection()
        self.audio.terminate()
