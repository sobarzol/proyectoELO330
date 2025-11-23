package com.conference.client;

import com.conference.grpc.*;
import com.google.protobuf.ByteString;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class FileTransferManager {
    private final ConferenceServiceGrpc.ConferenceServiceStub asyncStub;
    private final StreamObserver<ConferenceData> requestObserver; // Observer for main channel
    private final String senderName;
    private static final int CHUNK_SIZE = 1024 * 64; // 64KB chunks
    private static final java.time.format.DateTimeFormatter TIME_FORMATTER = java.time.format.DateTimeFormatter.ofPattern("HH:mm");

    private static class PendingTransfer {
        final String originalSender;
        final long fileSize;
        PendingTransfer(String originalSender, long fileSize) {
            this.originalSender = originalSender;
            this.fileSize = fileSize;
        }
    }

    // State for P2P and broadcast transfers
    private final java.util.Map<String, PendingTransfer> pendingP2PTransfers = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Long> pendingBroadcasts = new java.util.concurrent.ConcurrentHashMap<>();


    public FileTransferManager(ConferenceServiceGrpc.ConferenceServiceStub asyncStub, StreamObserver<ConferenceData> requestObserver, String senderName) {
        this.asyncStub = asyncStub;
        this.requestObserver = requestObserver;
        this.senderName = senderName;
    }

    // --- Message Printing ---
    private void printMessage(String message) {
        System.out.print("\r\u001b[2K"); // Clear line
        System.out.println(message);
        printPrompt();
    }

    private void printPrompt() {
        System.out.print("[" + java.time.LocalDateTime.now().format(TIME_FORMATTER) + "] Tú: ");
        System.out.flush();
    }
    
    // --- Broadcast File Logic ---

    public void registerBroadcastTransfer(String transferId, long fileSize) {
        pendingBroadcasts.put(transferId, fileSize);
    }

    public void broadcastFile(String filePath, String roomId) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            printMessage("❌ Error: El archivo no existe: " + filePath);
            return;
        }
        try {
            long fileSize = Files.size(path);
            String filename = path.getFileName().toString();
            String transferId = UUID.randomUUID().toString();

            printMessage("📢 Anunciando archivo a la sala: '" + filename + "'...");

            // 1. Announce the file on the main channel
            BroadcastFileAnnouncement announcement = BroadcastFileAnnouncement.newBuilder()
                .setFilename(filename)
                .setFileSize(fileSize)
                .setTransferId(transferId)
                .build();
            
            ConferenceData data = ConferenceData.newBuilder()
                .setSender(senderName)
                .setRoomId(roomId)
                .setFileAnnouncement(announcement)
                .build();
            
            requestObserver.onNext(data);

            // 2. Immediately start the sender stream
            startFileStreamSender(path, transferId);

        } catch (IOException e) {
            printMessage("❌ Error al leer el archivo: " + e.getMessage());
        }
    }

    public void downloadBroadcastFile(String transferId, String savePath) {
        Long fileSize = pendingBroadcasts.get(transferId);
        if (fileSize == null) {
            printMessage("❌ Error: No se encontró anuncio para la transferencia " + transferId);
            return;
        }
        printMessage("📥 Preparando para descargar archivo " + transferId + "...");
        startFileStreamReceiver(transferId, savePath, fileSize);
    }

    // --- P2P File Transfer Logic ---

    public void registerPendingP2PTransfer(String transferId, String originalSender, long fileSize) {
        pendingP2PTransfers.put(transferId, new PendingTransfer(originalSender, fileSize));
    }

    public void uploadFile(String recipient, String filePath, String roomId) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            printMessage("❌ Error: El archivo no existe: " + filePath);
            return;
        }
        try {
            long fileSize = Files.size(path);
            String filename = path.getFileName().toString();
            String transferId = UUID.randomUUID().toString();
            printMessage("⏳ Solicitando enviar '" + filename + "' a " + recipient + "...");
            FileTransferRequest request = FileTransferRequest.newBuilder()
                    .setSender(senderName).setRecipient(recipient).setRoomId(roomId)
                    .setFilename(filename).setFileSize(fileSize).setTransferId(transferId)
                    .setTimestamp(Instant.now().getEpochSecond()).build();

            asyncStub.requestFileTransfer(request, new StreamObserver<FileTransferResponse>() {
                @Override
                public void onNext(FileTransferResponse response) {
                    if (response.getAccepted()) {
                        printMessage("✅ " + recipient + " aceptó el archivo. Iniciando transferencia...");
                        startFileStreamSender(path, transferId);
                    } else {
                        printMessage("⛔ " + recipient + " rechazó el archivo.");
                    }
                }
                @Override
                public void onError(Throwable t) { printMessage("❌ Error en la solicitud de transferencia: " + t.getMessage()); }
                @Override
                public void onCompleted() {}
            });
        } catch (IOException e) {
            printMessage("❌ Error al leer el archivo: " + e.getMessage());
        }
    }

    public void acceptFile(String transferId, String savePath, String roomId) {
        PendingTransfer pending = pendingP2PTransfers.get(transferId);
        if (pending == null) {
            printMessage("❌ Error: No se encontró información para la transferencia " + transferId);
            return;
        }
        printMessage("👍 Aceptando archivo " + transferId + " de " + pending.originalSender + "...");
        FileTransferResponse response = FileTransferResponse.newBuilder()
                .setTransferId(transferId).setAccepted(true).setSender(senderName)
                .setRecipient(pending.originalSender).setRoomId(roomId).build();

        asyncStub.respondFileTransfer(response, new StreamObserver<FileTransferResponse>() {
            @Override
            public void onNext(FileTransferResponse value) {}
            @Override
            public void onError(Throwable t) { printMessage("❌ Error al enviar aceptación: " + t.getMessage()); }
            @Override
            public void onCompleted() {
                printMessage("📥 Conectando para recibir archivo...");
                startFileStreamReceiver(transferId, savePath, pending.fileSize);
                pendingP2PTransfers.remove(transferId);
            }
        });
    }

    public void rejectFile(String transferId, String roomId) {
        PendingTransfer pending = pendingP2PTransfers.get(transferId);
        if (pending == null) { return; }
        printMessage("👎 Rechazando archivo " + transferId + " de " + pending.originalSender + "...");
        FileTransferResponse response = FileTransferResponse.newBuilder()
                .setTransferId(transferId).setAccepted(false).setSender(senderName)
                .setRecipient(pending.originalSender).setRoomId(roomId).build();
        asyncStub.respondFileTransfer(response, new StreamObserver<FileTransferResponse>() {
            @Override public void onNext(FileTransferResponse v) {}
            @Override public void onError(Throwable t) { printMessage("❌ Error al enviar rechazo: " + t.getMessage());}
            @Override public void onCompleted() {
                printMessage("Archivo rechazado correctamente.");
                pendingP2PTransfers.remove(transferId);
            }
        });
    }

    // --- Stream Workers (reused for P2P and broadcast) ---

    private void updateProgress(String action, long current, long total) {
        if (total <= 0) return;
        int percentage = (int) ((current * 100) / total);
        StringBuilder bar = new StringBuilder(60);
        bar.append("\r\u001b[2K");
        bar.append(String.format("%s %d%% [", action, percentage));
        for (int i = 0; i < 50; i++) {
            if (i < percentage / 2) bar.append("=");
            else bar.append(" ");
        }
        bar.append("]");
        System.out.print(bar.toString());
        System.out.flush();
    }

    private void startFileStreamSender(Path path, String transferId) {
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("role", Metadata.ASCII_STRING_MARSHALLER), "sender");
        metadata.put(Metadata.Key.of("transfer-id", Metadata.ASCII_STRING_MARSHALLER), transferId);
        var stubWithMetadata = asyncStub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
        StreamObserver<FileChunk> requestObserver = stubWithMetadata.transferFile(new StreamObserver<>() {
            @Override public void onNext(FileChunk v) {}
            @Override public void onError(Throwable t) {
                System.out.println();
                printMessage("❌ Error durante el envío del archivo: " + t.getMessage());
            }
            @Override public void onCompleted() {
                System.out.println();
                printMessage("✅ Archivo enviado exitosamente.");
            }
        });
        try (InputStream stream = Files.newInputStream(path)) {
            long fileSize = Files.size(path);
            byte[] buffer = new byte[CHUNK_SIZE];
            long totalBytesSent = 0;
            int chunkNumber = 0, bytesRead;
            while ((bytesRead = stream.read(buffer)) != -1) {
                totalBytesSent += bytesRead;
                requestObserver.onNext(FileChunk.newBuilder().setTransferId(transferId)
                    .setData(ByteString.copyFrom(buffer, 0, bytesRead)).setChunkNumber(chunkNumber++).setIsLast(false).build());
                updateProgress("Enviando", totalBytesSent, fileSize);
            }
            requestObserver.onNext(FileChunk.newBuilder().setTransferId(transferId)
                .setData(ByteString.EMPTY).setChunkNumber(chunkNumber).setIsLast(true).build());
            requestObserver.onCompleted();
        } catch (Exception e) {
            System.out.println();
            printMessage("❌ Error leyendo archivo local: " + e.getMessage());
            requestObserver.onError(e);
        }
    }

    private void startFileStreamReceiver(String transferId, String savePath, long fileSize) {
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("role", Metadata.ASCII_STRING_MARSHALLER), "receiver");
        metadata.put(Metadata.Key.of("transfer-id", Metadata.ASCII_STRING_MARSHALLER), transferId);
        var stubWithMetadata = asyncStub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
        AtomicBoolean success = new AtomicBoolean(false);
        AtomicLong totalBytesReceived = new AtomicLong(0);
        stubWithMetadata.transferFile(new StreamObserver<>() {
            FileOutputStream fileOutputStream = null;
            @Override public void onNext(FileChunk chunk) {
                try {
                    if (fileOutputStream == null) fileOutputStream = new FileOutputStream(savePath);
                    if (!chunk.getData().isEmpty()) {
                        byte[] data = chunk.getData().toByteArray();
                        fileOutputStream.write(data);
                        updateProgress("Recibiendo", totalBytesReceived.addAndGet(data.length), fileSize);
                    }
                    if (chunk.getIsLast()) success.set(true);
                } catch (IOException e) {
                    System.out.println();
                    printMessage("❌ Error escribiendo archivo: " + e.getMessage());
                    throw new RuntimeException(e);
                }
            }
            @Override public void onError(Throwable t) {
                System.out.println();
                printMessage("❌ Error recibiendo archivo: " + t.getMessage());
                closeFile();
            }
            @Override public void onCompleted() {
                closeFile();
                System.out.println();
                if (success.get()) printMessage("✅ Archivo recibido y guardado en: " + savePath);
                else printMessage("⚠️ Transferencia finalizada pero sin confirmación de éxito total.");
            }
            private void closeFile() {
                if (fileOutputStream != null) try { fileOutputStream.close(); } catch (IOException e) { e.printStackTrace(); }
            }
        });
    }
}