package com.chat.client;

import com.chat.grpc.ChatMessage;
import com.chat.grpc.ChatServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ChatClient {
    private final ManagedChannel channel;
    private final ChatServiceGrpc.ChatServiceStub asyncStub;
    private String sender;
    private String roomId;
    private AudioStreamer audioStreamer;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    public ChatClient(String host, int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .defaultLoadBalancingPolicy("pick_first")
                .build();
        this.asyncStub = ChatServiceGrpc.newStub(channel);
    }

    // Helper para imprimir mensajes (sin redibujar el prompt)
    private void printMessage(String message) {
        String ansiClearLine = "\r\u001b[2K";
        System.out.print(ansiClearLine + message + "\n");
        System.out.flush();
    }

    public void shutdown() throws InterruptedException {
        if (audioStreamer != null && audioStreamer.isGrpcStreamActive()) {
            audioStreamer.stopAudioConnection();
        }
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    public void joinChatRoom(String sender, String roomId) throws InterruptedException {
        this.sender = sender;
        this.roomId = roomId;
        this.audioStreamer = new AudioStreamer(asyncStub, sender, roomId);

        final CountDownLatch finishLatch = new CountDownLatch(1);

        StreamObserver<ChatMessage> responseObserver = new StreamObserver<ChatMessage>() {
            @Override
            public void onNext(ChatMessage message) {
                // Mostrar solo mensajes de otros (evitar duplicados por echo local)
                if (!message.getSender().equals(ChatClient.this.sender)) {
                    LocalDateTime dateTime = LocalDateTime.ofInstant(
                            Instant.ofEpochSecond(message.getTimestamp()),
                            ZoneId.systemDefault()
                    );
                    String ansiClearLine = "\r\u001b[2K";
                    
                    String formattedMessage = "[" + dateTime.format(TIME_FORMATTER) + "] "
                            + message.getSender() + ": " + message.getMessage();

                    System.out.print(ansiClearLine + formattedMessage + "\n");
                } else {
                    // Si es mi propio mensaje, solo limpiamos la línea actual (que debería estar vacía tras el Enter)
                    // para asegurar que el prompt se dibuje limpio
                    System.out.print("\r\u001b[2K");
                }
                
                System.out.flush();
                // Añadir esta línea para redibujar el prompt después de cualquier mensaje
                System.out.print("[" + LocalDateTime.now().format(TIME_FORMATTER) + "] Tú: ");
                System.out.flush();
            }

            @Override
            public void onError(Throwable t) {
                System.err.println("\nError en la conexión de texto: " + t.getMessage()); // Mantener System.err para errores
                finishLatch.countDown();
            }

            @Override
            public void onCompleted() {
                printMessage("La conexión de texto ha sido cerrada.");
                finishLatch.countDown();
            }
        };

        final StreamObserver<ChatMessage> requestObserver = asyncStub.joinChatRoom(responseObserver);

        try {
            ChatMessage joinMessage = ChatMessage.newBuilder()
                    .setSender(sender)
                    .setMessage(sender + " se ha unido a la sala.")
                    .setRoomId(roomId)
                    .setTimestamp(Instant.now().getEpochSecond())
                    .setTraceId(UUID.randomUUID().toString())
                    .build();
            requestObserver.onNext(joinMessage);

            System.out.println("✅ Conectado exitosamente como '" + sender + "' en sala '" + roomId + "'\n");
            printMessage("Ya puedes chatear. Escribe tu mensaje y presiona Enter.");
            printMessage("Escribe /help para ver todos los comandos disponibles.");

            Thread inputThread = new Thread(() -> {
                Scanner scanner = new Scanner(System.in);
                while (!Thread.currentThread().isInterrupted() && finishLatch.getCount() > 0) {
                    // El prompt se redibuja por printMessage o responseObserver.onNext.
                    // Si no ha habido actividad, esperamos la entrada sin un prompt explícito aquí.
                    // El primer prompt se imprime en joinChatRoom.
                    if (scanner.hasNextLine()) {
                        String line = scanner.nextLine();
                        String trimmedLine = line.trim();

                        if (trimmedLine.equalsIgnoreCase("/help")) {
                            printHelp();
                            continue;
                        } else if (trimmedLine.equalsIgnoreCase("/quit") ||
                                   trimmedLine.equalsIgnoreCase("/exit") ||
                                   trimmedLine.equalsIgnoreCase("/disconnect")) {

                            printMessage("Saliendo del chat...");
                            ChatMessage leaveMessage = ChatMessage.newBuilder()
                                    .setSender(ChatClient.this.sender)
                                    .setMessage(ChatClient.this.sender + " ha salido de la sala.")
                                    .setRoomId(ChatClient.this.roomId)
                                    .setTimestamp(Instant.now().getEpochSecond())
                                    .setTraceId(UUID.randomUUID().toString())
                                    .build();
                            requestObserver.onNext(leaveMessage);
                            requestObserver.onCompleted(); // Completes client's text stream
                            finishLatch.countDown(); // Desbloquear el hilo principal inmediatamente
                            break; // Exits inputThread loop
                        } else if (trimmedLine.equalsIgnoreCase("/mic on")) {
                            if (!audioStreamer.isGrpcStreamActive()) audioStreamer.startAudioConnection();
                            audioStreamer.startSpeakers();
                            audioStreamer.startMic();
                            continue;
                        } else if (trimmedLine.equalsIgnoreCase("/mic off")) {
                            audioStreamer.stopMic();
                            audioStreamer.stopSpeakers();
                            if (!audioStreamer.isMicActive() && !audioStreamer.isSpeakersActive()) {
                                audioStreamer.stopAudioConnection();
                            }
                            continue;
                        } else if (trimmedLine.equalsIgnoreCase("/listen on")) {
                            if (!audioStreamer.isGrpcStreamActive()) audioStreamer.startAudioConnection();
                            audioStreamer.startSpeakers();
                            continue;
                        } else if (trimmedLine.equalsIgnoreCase("/listen off")) {
                            audioStreamer.stopSpeakers();
                            if (!audioStreamer.isMicActive() && !audioStreamer.isSpeakersActive()) {
                                audioStreamer.stopAudioConnection();
                            }
                            continue;
                        }

                        if (!trimmedLine.isEmpty()) {
                            ChatMessage chatMessage = ChatMessage.newBuilder()
                                    .setSender(ChatClient.this.sender)
                                    .setMessage(trimmedLine)
                                    .setRoomId(ChatClient.this.roomId)
                                    .setTimestamp(Instant.now().getEpochSecond())
                                    .setTraceId(UUID.randomUUID().toString())
                                    .build();
                            requestObserver.onNext(chatMessage);
                        }
                    } else {
                        requestObserver.onCompleted();
                        break;
                    }
                }
                scanner.close();
            System.exit(0);
            });
            inputThread.setDaemon(true);
            inputThread.start();

            finishLatch.await();

        } catch (RuntimeException e) {
            requestObserver.onError(e);
            throw e;
        } finally {
            if (audioStreamer != null && audioStreamer.isGrpcStreamActive()) {
                audioStreamer.stopAudioConnection();
            }
        }
    }

    private static void printWelcome() {
        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("           CHAT gRPC - Cliente Java");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
    }

    private static void printHelp() {
        System.out.println("\n═══════════════════════════════════════════════════════");
        System.out.println("           COMANDOS DISPONIBLES");
        System.out.println("═══════════════════════════════════════════════════════");
        System.out.println("\n📝 Comandos de Chat:");
        System.out.println("  /help                          - Mostrar esta ayuda");
        System.out.println("  /quit, /exit, /disconnect      - Salir del chat");
        System.out.println("\n🎤 Comandos de Audio:");
        System.out.println("  /mic on                        - Activar micrófono y altavoces");
        System.out.println("  /mic off                       - Desactivar micrófono y altavoces");
        System.out.println("  /listen on                     - Activar solo altavoces");
        System.out.println("  /listen off                    - Desactivar altavoces");
        System.out.println("\n═══════════════════════════════════════════════════════\n");
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        printWelcome();

        // Pedir configuración del servidor
        System.out.print("Dirección del servidor [localhost]: ");
        String host = scanner.nextLine().trim();
        if (host.isEmpty()) {
            host = "localhost";
        }

        System.out.print("Puerto del servidor [50051]: ");
        String portStr = scanner.nextLine().trim();
        int port = 50051;
        if (!portStr.isEmpty()) {
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                System.err.println("Puerto inválido, usando 50051");
                port = 50051;
            }
        }

        String serverAddr = host + ":" + port;
        System.out.println("\n🔌 Conectando a " + serverAddr + "...");

        ChatClient client = null;
        try {
            client = new ChatClient(host, port);
            System.out.println("✅ Conectado al servidor exitosamente\n");
        } catch (Exception e) {
            System.out.println("\n❌ Error de conexión");
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("No se pudo conectar al servidor " + serverAddr + "\n");
            System.out.println("Posibles causas:");
            System.out.println("  • El servidor no está ejecutándose");
            System.out.println("  • La dirección o puerto son incorrectos");
            System.out.println("  • Hay un firewall bloqueando la conexión");
            System.out.println("  • No hay conexión de red al servidor\n");
            System.out.println("Intenta:");
            System.out.println("  1. Verificar que el servidor esté corriendo: make server");
            System.out.println("  2. Verificar la dirección y puerto del servidor");
            System.out.println("  3. Usar localhost:50051 si el servidor es local");
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            System.exit(1);
            return;
        }

        // Pedir información de la sala
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("           UNIRSE A UNA SALA DE CHAT");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        System.out.print("\n🏠 ID de la sala (ej: 1, sala1, proyecto): ");
        String roomId = scanner.nextLine().trim();

        if (roomId.isEmpty()) {
            System.err.println("¡El ID de la sala no puede estar vacío!");
            try {
                client.shutdown();
            } catch (InterruptedException e) {
                // Ignore
            }
            return;
        }

        String sender = "";
        while (sender.isEmpty()) {
            System.out.print("👤 Tu nombre de usuario: ");
            sender = scanner.nextLine().trim();

            if (sender.isEmpty()) {
                System.out.println("El nombre no puede estar vacío. Intenta de nuevo.");
            }
        }

        try {
            client.joinChatRoom(sender, roomId);
        } catch (InterruptedException e) {
            System.err.println("Chat interrumpido: " + e.getMessage());
        } finally {
            try {
                client.shutdown();
            } catch (InterruptedException e) {
                System.err.println("Error al cerrar: " + e.getMessage());
            }
        }
    }
}
