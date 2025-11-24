package com.conference.client;

import com.conference.grpc.*;
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
import java.util.concurrent.atomic.AtomicBoolean;

public class ChatClient {

    // Enum to control the flow of the main application loop
    public enum SessionResult {
        NORMAL_LEAVE,
        QUIT_APPLICATION,
        CONNECTION_ERROR
    }

    private final ManagedChannel channel;
    private final ConferenceServiceGrpc.ConferenceServiceStub asyncStub;
    private String sender;
    private String roomId;
    private AudioStreamer audioStreamer;
    private FileTransferManager fileTransferManager;
    private StreamObserver<ConferenceData> requestObserver;
    private CountDownLatch finishLatch;
    private SessionResult sessionResult;


    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    public ChatClient(String host, int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .defaultLoadBalancingPolicy("pick_first")
                .build();
        this.asyncStub = ConferenceServiceGrpc.newStub(channel);
    }

    private synchronized void printMessage(String message) {
        System.out.print("\r\u001b[2K");
        System.out.println(message);
    }

    private synchronized void printPrompt() {
        System.out.print("[" + LocalDateTime.now().format(TIME_FORMATTER) + "] " + this.sender + ": ");
        System.out.flush();
    }

    public void shutdown() {
        if (requestObserver != null) {
            try { requestObserver.onCompleted(); } catch (Exception e) { /* Ignore */ }
        }
        if (audioStreamer != null && audioStreamer.isAudioActive()) {
            audioStreamer.stopAudio();
        }
        try {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public SessionResult startChat(String sender, String roomId) throws InterruptedException {
        this.sender = sender;
        this.roomId = roomId;
        this.finishLatch = new CountDownLatch(1);
        this.sessionResult = SessionResult.CONNECTION_ERROR; // Default to error
        final AtomicBoolean connectionSuccessful = new AtomicBoolean(false);

        StreamObserver<ConferenceData> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(ConferenceData data) {
                if (data.getSender().equals(ChatClient.this.sender) && data.getPayloadCase() != ConferenceData.PayloadCase.COMMAND) {
                    return;
                }
                switch (data.getPayloadCase()) {
                    case TEXT_MESSAGE:
                        ChatMessage chat = data.getTextMessage();
                        if (data.getSender().equals("Sistema-FileTransfer") && chat.getContent().startsWith("FILE_REQUEST:")) {
                            handleP2PFileRequestNotification(chat.getContent());
                        } else {
                            LocalDateTime dt = LocalDateTime.ofInstant(Instant.ofEpochSecond(chat.getTimestamp()), ZoneId.systemDefault());
                            String content = chat.getContent();
                            
                            if (content.startsWith("(private)")) {
                                printMessage(String.format("[%s] %s", dt.format(TIME_FORMATTER), content));
                            } else {
                                printMessage(String.format("[%s] %s: %s", dt.format(TIME_FORMATTER), data.getSender(), content));
                            }
                        }
                        break;
                    case FILE_ANNOUNCEMENT:
                        BroadcastFileAnnouncement announce = data.getFileAnnouncement();
                        String size = String.format("%.2f KiB", (double) announce.getFileSize() / 1024.0);
                        printMessage(String.format("%s está compartiendo '%s' (%s).", data.getSender(), announce.getFilename(), size));
                        printMessage(String.format("   Para descargar, usa: /download %s <ruta_destino>", announce.getTransferId()));
                        fileTransferManager.registerBroadcastTransfer(announce.getTransferId(), announce.getFileSize());
                        break;
                    case AUDIO_CHUNK:
                        if (audioStreamer != null && audioStreamer.isSpeakersActive()) {
                            audioStreamer.playAudioChunk(data.getAudioChunk().getData().toByteArray());
                        }
                        break;
                    case COMMAND:
                        com.conference.grpc.Command cmd = data.getCommand();
                        if (cmd.getType().equals("ERROR")) {
                            System.out.println("\r\u001b[2K Error del Servidor: " + cmd.getValue());
                            finishLatch.countDown();
                        } else if (cmd.getType().equals("WELCOME")) {
                            connectionSuccessful.set(true);
                            System.out.print("\r\u001b[2K");
                            System.out.println("Conectado exitosamente como '" + sender + "' en sala '" + roomId + "'");
                            System.out.println("Ya puedes chatear. Escribe /help para ver todos los comandos.");
                        } else {
                            printMessage(String.format("[SERVER] %s: %s", cmd.getType(), cmd.getValue()));
                        }
                        break;
                    default:
                        break;
                }
                if (connectionSuccessful.get()) {
                    printPrompt();
                }
            }
            @Override public void onError(Throwable t) { System.out.println("\r\u001b[2K Error en la conexión: " + t.getMessage()); finishLatch.countDown(); }
            @Override public void onCompleted() {
                // If result is not already set to QUIT, it means it's a normal leave/disconnect.
                if (sessionResult != SessionResult.QUIT_APPLICATION) {
                    sessionResult = SessionResult.NORMAL_LEAVE;
                }
                System.out.println("\r\u001b[2K🔌 Desconectado de la sala.");
                finishLatch.countDown();
            }
        };

        requestObserver = asyncStub.joinConference(responseObserver);
        this.audioStreamer = new AudioStreamer(requestObserver, sender, roomId);
        this.fileTransferManager = new FileTransferManager(asyncStub, requestObserver, sender);

        try {
            ConferenceData joinMessage = ConferenceData.newBuilder().setSender(sender).setRoomId(roomId)
                    .setCommand(com.conference.grpc.Command.newBuilder().setType("JOIN").build()).build();
            requestObserver.onNext(joinMessage);
            Thread inputThread = new Thread(this::handleUserInput);
            inputThread.start();
            finishLatch.await();
            inputThread.interrupt();
        } catch (RuntimeException e) {
            requestObserver.onError(e);
            throw e;
        }
        return this.sessionResult;
    }

    private void handleUserInput() {
        Scanner scanner = new Scanner(System.in);
        printPrompt();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (scanner.hasNextLine()) {
                    String line = scanner.nextLine().trim();
                    if (line.isEmpty()) { printPrompt(); continue; }
                    if (line.startsWith("/")) {
                        if (handleCommand(line)) break;
                    } else {
                        ChatMessage chat = ChatMessage.newBuilder().setSender(this.sender).setContent(line).setRoomId(this.roomId)
                                .setTimestamp(Instant.now().getEpochSecond()).setTraceId(UUID.randomUUID().toString()).build();
                        ConferenceData data = ConferenceData.newBuilder().setSender(this.sender).setRoomId(this.roomId)
                                .setTextMessage(chat).build();
                        requestObserver.onNext(data);
                        printPrompt();
                    }
                } else { break; }
            } catch (Exception e) { break; }
        }
    }

    private boolean handleCommand(String commandLine) {
        String[] parts = commandLine.split(" ", 3);
        String command = parts[0].toLowerCase();
        boolean shouldBreakLoop = false;

        switch (command) {
            case "/help": printHelp(); printPrompt(); break;
            case "/quit": case "/exit":
                printMessage("Cerrando aplicación...");
                this.sessionResult = SessionResult.QUIT_APPLICATION;
                requestObserver.onCompleted();
                shouldBreakLoop = true;
                break;
            case "/leave":
                 printMessage("Saliendo de la sala...");
                 this.sessionResult = SessionResult.NORMAL_LEAVE;
                 requestObserver.onCompleted();
                 shouldBreakLoop = true;
                 break;
            case "/msg":
                if (parts.length >= 3) {
                    PrivateMessage pvtMsg = PrivateMessage.newBuilder().setRecipientId(parts[1]).setContent(parts[2]).build();
                    ConferenceData data = ConferenceData.newBuilder().setSender(sender).setRoomId(roomId).setPrivateMessage(pvtMsg).build();
                    requestObserver.onNext(data);
                } else { printMessage("Uso: /msg <usuario> <mensaje>"); }
                printPrompt();
                break;
            default:
                handleOtherCommands(command, parts);
                break;
        }
        return shouldBreakLoop;
    }

    private void handleOtherCommands(String command, String[] parts) {
        switch(command) {
            case "/mic":
                if (parts.length > 1 && parts[1].equalsIgnoreCase("on")) audioStreamer.startAudio();
                else if (parts.length > 1 && parts[1].equalsIgnoreCase("off")) audioStreamer.stopAudio();
                else printMessage("Uso: /mic <on|off>");
                printPrompt();
                break;
            case "/upload":
                if (parts.length == 3) fileTransferManager.uploadFile(parts[1], parts[2], roomId);
                else printMessage("Uso: /upload <usuario> <ruta_archivo>");
                break;
            case "/upload-all":
                if (parts.length == 2) fileTransferManager.broadcastFile(parts[1], roomId);
                else printMessage("Uso: /upload-all <ruta_archivo>");
                break;
            case "/download":
                if (parts.length == 3) fileTransferManager.downloadBroadcastFile(parts[1], parts[2]);
                else printMessage("Uso: /download <id_transferencia> <ruta_destino>");
                break;
            case "/accept":
                 if (parts.length == 3) fileTransferManager.acceptFile(parts[1], parts[2], roomId);
                 else printMessage("Uso: /accept <transferId> <ruta_destino>");
                break;
            case "/reject":
                if (parts.length == 2) fileTransferManager.rejectFile(parts[1], roomId);
                else printMessage("Uso: /reject <transferId>");
                break;
            default:
                printMessage("Comando no reconocido: " + command);
                printPrompt();
                break;
        }
    }
    
    private void handleP2PFileRequestNotification(String message) {
        String[] parts = message.split(":");
        if (parts.length >= 6) {
            String transferId = parts[1], fileSender = parts[2], filename = parts[3];
            try {
                long fileSize = Long.parseLong(parts[4]);
                fileTransferManager.registerPendingP2PTransfer(transferId, fileSender, fileSize);
                printMessage("\nSolicitud de archivo 1-a-1 recibida:");
                printMessage("  De: " + fileSender);
                printMessage("  Archivo: " + filename + " (" + fileSize + " bytes)");
                printMessage("  Para aceptar: /accept " + transferId + " <ruta_destino>");
                printMessage("  Para rechazar: /reject " + transferId);
            } catch (NumberFormatException e) {
                printMessage("Error: Formato de tamaño de archivo inválido en la notificación.");
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
        System.out.println("                   COMANDOS DISPONIBLES");
        System.out.println("═══════════════════════════════════════════════════════");
        System.out.println("\n\uD83D\uDCDD Comandos de Chat y Sala:");
        System.out.println("  /help                          - Mostrar esta ayuda");
        System.out.println("  /msg <usuario> <mensaje>       - Enviar un mensaje privado");
        System.out.println("  /leave                         - Salir de la sala actual para unirse a otra");
        System.out.println("  /quit, /exit                   - Cerrar la aplicación");
        System.out.println("\n\uD83C\uDFA4 Comandos de Audio:");
        System.out.println("  /mic <on|off>                  - Activar o desactivar micrófono y altavoces");
        System.out.println("\n\uD83D\uDCE4 Comandos de Archivos (1 a 1):");
        System.out.println("  /upload <usuario> <archivo>    - Enviar un archivo a un usuario");
        System.out.println("  /accept <id> <ruta>            - Aceptar transferencia");
        System.out.println("  /reject <id>                   - Rechazar transferencia");
        System.out.println("\n\uD83D\uDCE3 Comandos de Archivos (Sala Completa):");
        System.out.println("  /upload-all <archivo>          - Compartir un archivo con la sala");
        System.out.println("  /download <id> <ruta>          - Descargar un archivo compartido");
        System.out.println("\n═══════════════════════════════════════════════════════\n");
    }

    public static void main(String[] args) {
        printWelcome();
        Scanner scanner = new Scanner(System.in);
        System.out.print("Dirección del servidor [localhost]: ");
        String host = scanner.nextLine().trim();
        if (host.isEmpty()) host = "localhost";
        System.out.print("Puerto del servidor [50051]: ");
        String portStr = scanner.nextLine().trim();
        int port = portStr.isEmpty() ? 50051 : Integer.parseInt(portStr);
        ChatClient client = new ChatClient(host, port);
        System.out.println("\n──────────────────────────────────────────────────");
        System.out.println("                UNIRSE A UNA SALA");
        System.out.println("──────────────────────────────────────────────────");

        while (true) {
            
            System.out.print("\n🏠 ID de la sala (o escribe 'quit' para salir): ");
            String roomId = scanner.nextLine().trim();
            if (roomId.equalsIgnoreCase("quit")) break;

            if (roomId.isEmpty()) {
                System.err.println("❌ ¡El ID de la sala no puede estar vacíos!");
                continue;
            }

            System.out.print("👤 Tu nombre de usuario: ");
            String sender = scanner.nextLine().trim();

            

            if(sender.isEmpty()){
                System.err.println("❌ ¡El nombre de usuario no puede estar vacíos!");
                continue;
            }
            
            try {
                SessionResult result = client.startChat(sender, roomId);
                if (result == SessionResult.QUIT_APPLICATION) {
                    break;
                }
                // If NORMAL_LEAVE or CONNECTION_ERROR, the loop continues, allowing to join another room
            } catch (InterruptedException e) {
                System.err.println("Chat interrumpido: " + e.getMessage());
                break;
            }
        }
        
        System.out.println("Cerrando conexión...");
        client.shutdown();
        System.out.println("¡Adiós!");
    }
}
