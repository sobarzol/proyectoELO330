package com.chat.client;

import com.chat.grpc.AudioChunk;
import com.chat.grpc.ChatServiceGrpc;
import com.google.protobuf.ByteString;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Channel;
import io.grpc.stub.StreamObserver;

import javax.sound.sampled.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.io.IOException;

public class AudioStreamer {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final ChatServiceGrpc.ChatServiceStub asyncStub;
    private final String sender;
    private final String roomId;

    // Componentes de audio
    private AudioFormat audioFormat;
    private TargetDataLine microphone;
    private SourceDataLine speakers;

    // Control de gRPC Stream
    private StreamObserver<AudioChunk> requestObserver; // Para enviar audio al servidor
    private boolean grpcStreamActive = false;

    // Control de estados individuales
    private volatile boolean micActive = false;
    private volatile boolean speakersActive = false;

    private Thread micCaptureThread; // Hilo para capturar del micrófono

    public AudioStreamer(ChatServiceGrpc.ChatServiceStub asyncStub, String sender, String roomId) {
        this.asyncStub = asyncStub;
        this.sender = sender;
        this.roomId = roomId;
        this.audioFormat = getAudioFormat();
    }

    private AudioFormat getAudioFormat() {
        return new AudioFormat(44100, 16, 1, true, false); // 44.1kHz, 16bit, Mono, Signed, Little-endian
    }

    // Helper para imprimir mensajes Y redibujar el prompt
    private void printMessage(String message) {
        String ansiClearLine = "\r\u001b[2K";
        String prompt = "[" + LocalDateTime.now().format(TIME_FORMATTER) + "] Tú: ";
        System.out.print(ansiClearLine + message + "\n" + prompt);
        System.out.flush();
    }


    /**
     * Establece la conexión bidireccional gRPC para audio.
     * Esto DEBE llamarse antes de intentar usar el micrófono o los altavoces.
     */
    public void startAudioConnection() {
        if (grpcStreamActive) {
            printMessage("La conexión de audio gRPC ya está activa.");
            return;
        }

        // Observer para recibir audio del servidor y reproducirlo
        StreamObserver<AudioChunk> responseObserver = new StreamObserver<AudioChunk>() {
            @Override
            public void onNext(AudioChunk value) {
                if (speakersActive && speakers != null && speakers.isOpen()) {
                    byte[] audioData = value.getData().toByteArray();
                    speakers.write(audioData, 0, audioData.length);
                }
            }

            @Override
            public void onError(Throwable t) {
                String errorDetails;
                if (t instanceof io.grpc.StatusRuntimeException) {
                    errorDetails = ((io.grpc.StatusRuntimeException) t).getStatus().toString();
                } else {
                    errorDetails = t.getMessage();
                }
                System.err.println("Error en el stream de audio gRPC: " + errorDetails); // Mantener System.err para errores
                stopAudioConnection(); // Asegurarse de cerrar todo
            }

            @Override
            public void onCompleted() {
                printMessage("Recepción de audio gRPC finalizada.");
                stopAudioConnection(); // Asegurarse de cerrar todo
            }
        };

        // Adjuntar metadatos al contexto de la llamada gRPC
        ClientInterceptor headerInterceptor = new ClientInterceptor() {
            @Override
            public <ReqT, RespT> io.grpc.ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method,
                    io.grpc.CallOptions callOptions,
                    io.grpc.Channel next) {
                return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
                    @Override
                    public void start(ClientCall.Listener<RespT> responseListener, Metadata headersToAdd) {
                        headersToAdd.put(Metadata.Key.of("room-id", Metadata.ASCII_STRING_MARSHALLER), roomId);
                        headersToAdd.put(Metadata.Key.of("sender", Metadata.ASCII_STRING_MARSHALLER), sender);
                        super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(responseListener) {
                            // No es necesario añadir lógica aquí para el POC
                        }, headersToAdd);
                    }
                };
            }
        };

        requestObserver = asyncStub.withInterceptors(headerInterceptor).streamAudio(responseObserver);
        grpcStreamActive = true;
        printMessage("Conexión de audio gRPC establecida.");
    }

    /**
     * Cierra la conexión bidireccional gRPC para audio.
     * Esto detendrá tanto el envío como la recepción.
     */
    public void stopAudioConnection() {
        if (!grpcStreamActive) {
            return;
        }
        grpcStreamActive = false;
        stopMic(); // Asegurarse de detener el micrófono si está activo
        stopSpeakers(); // Asegurarse de detener los altavoces si están activos

        if (requestObserver != null) {
            requestObserver.onCompleted();
        }
        printMessage("Conexión de audio gRPC cerrada.");
    }

    /**
     * Inicia la captura de audio del micrófono y el envío al servidor.
     */
    public void startMic() {
        if (micActive) {
            printMessage("Micrófono ya activo.");
            return;
        }
        if (!grpcStreamActive) {
            System.err.println("Primero debes establecer la conexión gRPC de audio (/mic on o /listen on)."); // Mantener System.err
            return;
        }

        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, audioFormat);
            microphone = (TargetDataLine) AudioSystem.getLine(info);
            microphone.open(audioFormat);
            microphone.start();

            micActive = true;
            printMessage("Micrófono activado. Transmitiendo voz...");

            micCaptureThread = new Thread(() -> {
                byte[] buffer = new byte[1024]; // Tamaño del buffer
                while (micActive && requestObserver != null) {
                    int bytesRead = microphone.read(buffer, 0, buffer.length);
                    if (bytesRead > 0) {
                        AudioChunk chunk = AudioChunk.newBuilder()
                                .setData(ByteString.copyFrom(buffer, 0, bytesRead))
                                .build();
                        try {
                            requestObserver.onNext(chunk);
                        } catch (Exception e) {
                            System.err.println("Error al enviar chunk de audio: " + e.getMessage()); // Mantener System.err
                            micActive = false; // Detener el hilo si falla el envío
                        }
                    } else {
                        // System.err.println("DEBUG: Micrófono no leyó bytes, esperando..."); // DEBUG LOG - Eliminado
                        try {
                            Thread.sleep(100); // Esperar un poco si no hay bytes para evitar bucle ocupado
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
                microphone.close();
                printMessage("Captura de micrófono detenida.");
            });
            micCaptureThread.setDaemon(true);
            micCaptureThread.start();

        } catch (LineUnavailableException e) {
            System.err.println("Error al acceder al micrófono: " + e.getMessage()); // Mantener System.err
            micActive = false;
        }
    }

    /**
     * Detiene la captura de audio del micrófono.
     */
    public void stopMic() {
        if (!micActive) {
            return;
        }
        micActive = false;
        if (micCaptureThread != null) {
            micCaptureThread.interrupt(); // Interrumpir el hilo si está esperando
            try {
                micCaptureThread.join(500); // Esperar un poco a que termine
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (microphone != null && microphone.isOpen()) {
            microphone.stop();
            microphone.close();
        }
        printMessage("Micrófono detenido.");
    }

    /**
     * Inicia la reproducción de audio recibido por los altavoces.
     */
    public void startSpeakers() {
        if (speakersActive) {
            printMessage("Altavoces ya activos para reproducción.");
            return;
        }
        if (!grpcStreamActive) {
            System.err.println("Primero debes establecer la conexión gRPC de audio (/mic on o /listen on)."); // Mantener System.err
            return;
        }

        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
            speakers = (SourceDataLine) AudioSystem.getLine(info);
            speakers.open(audioFormat);
            speakers.start();
            speakersActive = true;
            printMessage("Altavoces activados para reproducción de audio.");
        } catch (LineUnavailableException e) {
            System.err.println("Error al acceder a los altavoces: " + e.getMessage()); // Mantener System.err
            speakersActive = false;
        }
    }

    /**
     * Detiene la reproducción de audio por los altavoces.
     */
    public void stopSpeakers() {
        if (!speakersActive) {
            return;
        }
        speakersActive = false;
        if (speakers != null && speakers.isOpen()) {
            speakers.drain();
            speakers.close();
        }
        printMessage("Altavoces detenidos.");
    }

    public boolean isMicActive() {
        return micActive;
    }

    public boolean isSpeakersActive() {
        return speakersActive;
    }

    public boolean isGrpcStreamActive() {
        return grpcStreamActive;
    }
}