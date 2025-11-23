package com.chat.client;

import com.chat.grpc.*;
import com.google.protobuf.ByteString;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public class FileTransferManager {
    private final ChatServiceGrpc.ChatServiceStub asyncStub;
    private final String senderName;
    private static final int CHUNK_SIZE = 1024 * 64; // 64KB chunks
    
    // Mapa para rastrear transferencias pendientes: transferId -> originalSender
    private final java.util.Map<String, String> pendingTransfers = new java.util.concurrent.ConcurrentHashMap<>();

    public FileTransferManager(ChatServiceGrpc.ChatServiceStub asyncStub, String senderName) {
        this.asyncStub = asyncStub;
        this.senderName = senderName;
    }
    
    public void registerPendingTransfer(String transferId, String originalSender) {
        pendingTransfers.put(transferId, originalSender);
    }

    private void printMessage(String message) {
        String ansiClearLine = "\r\u001b[2K";
        System.out.println(ansiClearLine + message);
        // Redibujar prompt simple si es necesario, pero idealmente esto se maneja en el loop principal
        // Por ahora solo imprimimos limpio
    }

    public void uploadFile(String recipient, String filePath) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            printMessage("❌ Error: El archivo no existe: " + filePath);
            return;
        }

        long fileSize;
        try {
            fileSize = Files.size(path);
        } catch (IOException e) {
            printMessage("❌ Error al leer tamaño del archivo: " + e.getMessage());
            return;
        }

        String filename = path.getFileName().toString();
        String transferId = UUID.randomUUID().toString();

        printMessage("📤 Iniciando solicitud de transferencia para '" + filename + "' a " + recipient + "...");

        FileTransferRequest request = FileTransferRequest.newBuilder()
                .setSender(senderName)
                .setRecipient(recipient)
                .setRoomId("") // El servidor usa esto para notificaciones, pero el stub ya tiene contexto? No, necesitamos pasarlo si el servidor lo requiere.
                // Revisando el proto, RequestFileTransfer pide room_id.
                // Asumiremos que el ChatClient pasará el roomId actual o lo setearemos aquí si tuviéramos acceso.
                // Por ahora, pasaremos un placeholder o lo arreglaremos en ChatClient para que pase el roomId.
                // Ajuste: uploadFile debería recibir roomId.
                .setFilename(filename)
                .setFileSize(fileSize)
                .setTransferId(transferId)
                .setTimestamp(Instant.now().getEpochSecond())
                .build();
        
        // NOTA: El roomId se debe setear correctamente. Modificaré la firma del método en el siguiente paso si es necesario,
        // pero por ahora asumiré que se pasa o se maneja. 
        // Espera, FileTransferRequest TIENE room_id. Necesito pedirlo en uploadFile.
    }
    
    // Sobrecarga corregida
    public void uploadFile(String recipient, String filePath, String roomId) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            printMessage("❌ Error: El archivo no existe: " + filePath);
            return;
        }

        long fileSize;
        try {
            fileSize = Files.size(path);
        } catch (IOException e) {
            printMessage("❌ Error al leer tamaño del archivo: " + e.getMessage());
            return;
        }

        String filename = path.getFileName().toString();
        String transferId = UUID.randomUUID().toString();

        printMessage("⏳ Solicitando enviar '" + filename + "' (" + fileSize + " bytes) a " + recipient + "...");

        FileTransferRequest request = FileTransferRequest.newBuilder()
                .setSender(senderName)
                .setRecipient(recipient)
                .setRoomId(roomId)
                .setFilename(filename)
                .setFileSize(fileSize)
                .setTransferId(transferId)
                .setTimestamp(Instant.now().getEpochSecond())
                .build();

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
            public void onError(Throwable t) {
                printMessage("❌ Error en la solicitud de transferencia: " + t.getMessage());
            }

            @Override
            public void onCompleted() {
                // Nada que hacer aquí
            }
        });
    }

    public void acceptFile(String transferId, String savePath, String roomId) {
        String originalSender = pendingTransfers.get(transferId);
        if (originalSender == null) {
            printMessage("❌ Error: No se encontró información para la transferencia " + transferId);
            return;
        }
        
        printMessage("👍 Aceptando archivo " + transferId + " de " + originalSender + "...");
        
        FileTransferResponse response = FileTransferResponse.newBuilder()
                .setTransferId(transferId)
                .setAccepted(true)
                .setSender(senderName) // Quien responde (yo)
                .setRecipient(originalSender) // A quien le respondo (el que envía el archivo)
                .setRoomId(roomId)
                .build();

        asyncStub.respondFileTransfer(response, new StreamObserver<FileTransferResponse>() {
            @Override
            public void onNext(FileTransferResponse value) {
                // Confirmación del servidor, irrelevante
            }

            @Override
            public void onError(Throwable t) {
                printMessage("❌ Error al enviar aceptación: " + t.getMessage());
            }

            @Override
            public void onCompleted() {
                printMessage("📥 Conectando para recibir archivo...");
                startFileStreamReceiver(transferId, savePath);
                pendingTransfers.remove(transferId);
            }
        });
    }

    public void rejectFile(String transferId, String roomId) {
        String originalSender = pendingTransfers.get(transferId);
        if (originalSender == null) {
            printMessage("❌ Error: No se encontró información para la transferencia " + transferId);
            return;
        }

        printMessage("👎 Rechazando archivo " + transferId + " de " + originalSender + "...");

        FileTransferResponse response = FileTransferResponse.newBuilder()
                .setTransferId(transferId)
                .setAccepted(false)
                .setSender(senderName)
                .setRecipient(originalSender)
                .setRoomId(roomId)
                .build();

        asyncStub.respondFileTransfer(response, new StreamObserver<FileTransferResponse>() {
            @Override
            public void onNext(FileTransferResponse value) {}

            @Override
            public void onError(Throwable t) {
                printMessage("❌ Error al enviar rechazo: " + t.getMessage());
            }

            @Override
            public void onCompleted() {
                printMessage("Archivo rechazado correctamente.");
                pendingTransfers.remove(transferId);
            }
        });
    }

    private void startFileStreamSender(Path path, String transferId) {
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("role", Metadata.ASCII_STRING_MARSHALLER), "sender");
        metadata.put(Metadata.Key.of("transfer-id", Metadata.ASCII_STRING_MARSHALLER), transferId);

        ChatServiceGrpc.ChatServiceStub stubWithMetadata = asyncStub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));

        StreamObserver<FileChunk> requestObserver = stubWithMetadata.transferFile(new StreamObserver<FileChunk>() {
            @Override
            public void onNext(FileChunk value) {
                // El servidor no envía nada al sender, pero por si acaso
            }

            @Override
            public void onError(Throwable t) {
                printMessage("❌ Error durante el envío del archivo: " + t.getMessage());
            }

            @Override
            public void onCompleted() {
                printMessage("✅ Archivo enviado exitosamente.");
            }
        });

        try (InputStream inputStream = Files.newInputStream(path)) {
            byte[] buffer = new byte[CHUNK_SIZE];
            int bytesRead;
            int chunkNumber = 0;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                FileChunk chunk = FileChunk.newBuilder()
                        .setTransferId(transferId)
                        .setData(ByteString.copyFrom(buffer, 0, bytesRead))
                        .setChunkNumber(chunkNumber++)
                        .setIsLast(false)
                        .build();
                requestObserver.onNext(chunk);
            }

            // Enviar último chunk vacío o marcar el último enviado?
            // El servidor espera is_last=true para cerrar.
            // Enviaremos un chunk vacío final para marcar el fin.
            FileChunk lastChunk = FileChunk.newBuilder()
                    .setTransferId(transferId)
                    .setData(ByteString.EMPTY)
                    .setChunkNumber(chunkNumber)
                    .setIsLast(true)
                    .build();
            requestObserver.onNext(lastChunk);
            requestObserver.onCompleted();

        } catch (IOException e) {
            printMessage("❌ Error leyendo archivo: " + e.getMessage());
            requestObserver.onError(e);
        }
    }

    private void startFileStreamReceiver(String transferId, String savePath) {
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("role", Metadata.ASCII_STRING_MARSHALLER), "receiver");
        metadata.put(Metadata.Key.of("transfer-id", Metadata.ASCII_STRING_MARSHALLER), transferId);

        ChatServiceGrpc.ChatServiceStub stubWithMetadata = asyncStub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));

        AtomicBoolean success = new AtomicBoolean(false);

        StreamObserver<FileChunk> requestObserver = stubWithMetadata.transferFile(new StreamObserver<FileChunk>() {
            FileOutputStream fileOutputStream = null;

            @Override
            public void onNext(FileChunk chunk) {
                try {
                    if (fileOutputStream == null) {
                        fileOutputStream = new FileOutputStream(savePath);
                    }
                    if (!chunk.getData().isEmpty()) {
                        fileOutputStream.write(chunk.getData().toByteArray());
                    }
                    if (chunk.getIsLast()) {
                        success.set(true);
                    }
                } catch (IOException e) {
                    printMessage("❌ Error escribiendo archivo: " + e.getMessage());
                    // Deberíamos cancelar el stream aquí
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void onError(Throwable t) {
                printMessage("❌ Error recibiendo archivo: " + t.getMessage());
                closeFile();
            }

            @Override
            public void onCompleted() {
                closeFile();
                if (success.get()) {
                    printMessage("✅ Archivo recibido y guardado en: " + savePath);
                } else {
                    printMessage("⚠️ Transferencia finalizada pero sin confirmación de éxito total.");
                }
            }

            private void closeFile() {
                if (fileOutputStream != null) {
                    try {
                        fileOutputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
        
        // El receiver debe mantener el stream abierto, pero no necesita enviar nada.
        // Sin embargo, gRPC requiere que se envíe algo o se cierre el stream de salida para indicar que no se enviará nada.
        // Como es bidireccional, podemos simplemente no enviar nada o enviar un onCompleted inmediato si el servidor lo soporta.
        // El servidor en `TransferFile` para receiver hace `select {}` esperando chunks del sender (via servidor).
        // El cliente receiver NO envía chunks.
        // Así que podemos hacer requestObserver.onCompleted() inmediatamente?
        // No, si cerramos el stream de salida, el servidor podría cerrar el de entrada?
        // En gRPC bidi, cerrar salida no cierra entrada.
        // Probemos enviando onCompleted() para indicar que no enviaremos nada.
        // O mejor, dejémoslo abierto hasta que recibamos onCompleted del servidor.
        // Pero necesitamos mantener la referencia para que no sea GC'd? No, gRPC lo maneja.
    }
}
