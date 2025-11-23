package com.conference.client;

import com.conference.grpc.AudioChunk;
import com.conference.grpc.ConferenceData;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;

import javax.sound.sampled.*;
import java.time.Instant;
import java.util.UUID;

public class AudioStreamer {

    private final StreamObserver<ConferenceData> requestObserver;
    private final String sender;
    private final String roomId;

    private AudioFormat audioFormat;
    private TargetDataLine microphone;
    private SourceDataLine speakers;

    private volatile boolean audioActive = false;
    private volatile boolean speakersActive = false;
    private Thread micCaptureThread;

    public AudioStreamer(StreamObserver<ConferenceData> requestObserver, String sender, String roomId) {
        this.requestObserver = requestObserver;
        this.sender = sender;
        this.roomId = roomId;
        this.audioFormat = new AudioFormat(44100, 16, 1, true, false); // 44.1kHz, 16bit, Mono, Signed, Little-endian
    }

    public void startAudio() {
        if (audioActive) {
            System.out.println("El audio ya está activo.");
            return;
        }
        try {
            // Init microphone
            DataLine.Info micInfo = new DataLine.Info(TargetDataLine.class, audioFormat);
            microphone = (TargetDataLine) AudioSystem.getLine(micInfo);
            microphone.open(audioFormat);
            microphone.start();

            // Init speakers
            DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class, audioFormat);
            speakers = (SourceDataLine) AudioSystem.getLine(speakerInfo);
            speakers.open(audioFormat);
            speakers.start();
            
            audioActive = true;
            speakersActive = true;
            System.out.println("🎤 Micrófono y altavoces activados.");

            // Start thread to capture and send audio
            micCaptureThread = new Thread(() -> {
                byte[] buffer = new byte[1024];
                while (audioActive) {
                    int bytesRead = microphone.read(buffer, 0, buffer.length);
                    if (bytesRead > 0) {
                        try {
                            AudioChunk audioChunk = AudioChunk.newBuilder()
                                    .setData(ByteString.copyFrom(buffer, 0, bytesRead))
                                    .build();
                            ConferenceData conferenceData = ConferenceData.newBuilder()
                                    .setSender(sender)
                                    .setRoomId(roomId)
                                    .setAudioChunk(audioChunk)
                                    .build();
                            requestObserver.onNext(conferenceData);
                        } catch (Exception e) {
                            System.err.println("Error al enviar audio: " + e.getMessage());
                            audioActive = false;
                        }
                    }
                }
            });
            micCaptureThread.setDaemon(true);
            micCaptureThread.start();

        } catch (LineUnavailableException e) {
            System.err.println("Error al acceder a dispositivo de audio: " + e.getMessage());
            audioActive = false;
        }
    }

    public void stopAudio() {
        if (!audioActive) {
            return;
        }
        audioActive = false;
        speakersActive = false;

        if (micCaptureThread != null) {
            micCaptureThread.interrupt();
        }
        if (microphone != null && microphone.isOpen()) {
            microphone.stop();
            microphone.close();
        }
        if (speakers != null && speakers.isOpen()) {
            speakers.drain();
            speakers.close();
        }
        System.out.println("🎤 Micrófono y altavoces desactivados.");
    }
    
    public void playAudioChunk(byte[] audioData) {
        if (speakersActive && speakers != null && speakers.isOpen()) {
            speakers.write(audioData, 0, audioData.length);
        }
    }

    public boolean isAudioActive() {
        return audioActive;
    }
    
    public boolean isSpeakersActive() {
        return speakersActive;
    }
}
