use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{Device, Stream, StreamConfig};
use std::sync::{Arc, Mutex};
use std::time::SystemTime;
use tokio::sync::mpsc;
use tokio_stream::wrappers::ReceiverStream;
use tonic::metadata::MetadataValue;
use tonic::transport::Channel;
use tonic::{Request, Streaming};

use crate::chat::{chat_service_client::ChatServiceClient, AudioChunk};

pub struct AudioStreamer {
    client: ChatServiceClient<Channel>,
    sender: String,
    room_id: String,
    grpc_stream_active: Arc<Mutex<bool>>,
    mic_active: Arc<Mutex<bool>>,
    speakers_active: Arc<Mutex<bool>>,
    mic_stream: Arc<Mutex<Option<Stream>>>,
    speaker_stream: Arc<Mutex<Option<Stream>>>,
}

impl AudioStreamer {
    pub fn new(client: ChatServiceClient<Channel>, sender: String, room_id: String) -> Self {
        AudioStreamer {
            client,
            sender,
            room_id,
            grpc_stream_active: Arc::new(Mutex::new(false)),
            mic_active: Arc::new(Mutex::new(false)),
            speakers_active: Arc::new(Mutex::new(false)),
            mic_stream: Arc::new(Mutex::new(None)),
            speaker_stream: Arc::new(Mutex::new(None)),
        }
    }

    fn print_message(&self, message: &str) {
        let current_time = chrono::Local::now().format("%H:%M");
        print!("\r\x1b[2K{}\n[{}] Tú: ", message, current_time);
        std::io::Write::flush(&mut std::io::stdout()).unwrap();
    }

    pub async fn start_audio_connection(&mut self) -> Result<(), Box<dyn std::error::Error>> {
        let mut active = self.grpc_stream_active.lock().unwrap();
        if *active {
            self.print_message("La conexión de audio gRPC ya está activa.");
            return Ok(());
        }

        // Crear canal para enviar audio
        let (tx, rx) = mpsc::channel::<AudioChunk>(32);

        // Crear metadatos
        let mut request = Request::new(ReceiverStream::new(rx));
        request
            .metadata_mut()
            .insert("sender", MetadataValue::try_from(&self.sender)?);
        request
            .metadata_mut()
            .insert("room-id", MetadataValue::try_from(&self.room_id)?);

        // Iniciar stream bidireccional
        let response = self.client.stream_audio(request).await?;
        let mut response_stream = response.into_inner();

        *active = true;
        drop(active);

        // Clonar valores para el thread
        let speakers_active = Arc::clone(&self.speakers_active);
        let speaker_stream = Arc::clone(&self.speaker_stream);
        let grpc_stream_active = Arc::clone(&self.grpc_stream_active);

        // Spawn task para recibir audio
        tokio::spawn(async move {
            while let Ok(Some(chunk)) = response_stream.message().await {
                let active = *speakers_active.lock().unwrap();
                if active {
                    // Aquí se debería reproducir el audio
                    // Por simplicidad, lo omitimos en esta implementación básica
                }
            }

            let mut active = grpc_stream_active.lock().unwrap();
            *active = false;
            println!("\r\x1b[2KRecepción de audio gRPC finalizada.");
        });

        self.print_message("Conexión de audio gRPC establecida.");
        Ok(())
    }

    pub fn stop_audio_connection(&mut self) {
        let mut active = self.grpc_stream_active.lock().unwrap();
        if !*active {
            return;
        }

        *active = false;
        drop(active);

        self.stop_mic();
        self.stop_speakers();

        self.print_message("Conexión de audio gRPC cerrada.");
    }

    pub fn start_mic(&mut self) -> Result<(), Box<dyn std::error::Error>> {
        let mut mic_active = self.mic_active.lock().unwrap();
        if *mic_active {
            self.print_message("Micrófono ya activo.");
            return Ok(());
        }

        let grpc_active = *self.grpc_stream_active.lock().unwrap();
        if !grpc_active {
            eprintln!("Primero debes establecer la conexión gRPC de audio (/mic on o /listen on).");
            return Err("Conexión gRPC no activa".into());
        }

        // Obtener dispositivo de audio
        let host = cpal::default_host();
        let device = host
            .default_input_device()
            .ok_or("No se encontró dispositivo de entrada")?;

        let config = device.default_input_config()?;

        // Crear stream de captura
        let stream = match config.sample_format() {
            cpal::SampleFormat::F32 => self.build_input_stream::<f32>(&device, &config.into())?,
            cpal::SampleFormat::I16 => self.build_input_stream::<i16>(&device, &config.into())?,
            cpal::SampleFormat::U16 => self.build_input_stream::<u16>(&device, &config.into())?,
            _ => return Err("Formato de muestra no soportado".into()),
        };

        stream.play()?;

        *self.mic_stream.lock().unwrap() = Some(stream);
        *mic_active = true;

        self.print_message("Micrófono activado. Transmitiendo voz...");
        Ok(())
    }

    fn build_input_stream<T>(
        &self,
        device: &Device,
        config: &StreamConfig,
    ) -> Result<Stream, Box<dyn std::error::Error>>
    where
        T: cpal::Sample,
    {
        let err_fn = |err| eprintln!("Error en el stream de audio: {}", err);

        let stream = device.build_input_stream(
            config,
            move |_data: &[T], _: &cpal::InputCallbackInfo| {
                // Aquí se debería enviar el audio al servidor
                // Por simplicidad, lo omitimos en esta implementación básica
            },
            err_fn,
            None,
        )?;

        Ok(stream)
    }

    pub fn stop_mic(&mut self) {
        let mut mic_active = self.mic_active.lock().unwrap();
        if !*mic_active {
            return;
        }

        *mic_active = false;
        *self.mic_stream.lock().unwrap() = None;

        self.print_message("Micrófono detenido.");
    }

    pub fn start_speakers(&mut self) -> Result<(), Box<dyn std::error::Error>> {
        let mut speakers_active = self.speakers_active.lock().unwrap();
        if *speakers_active {
            self.print_message("Altavoces ya activos para reproducción.");
            return Ok(());
        }

        let grpc_active = *self.grpc_stream_active.lock().unwrap();
        if !grpc_active {
            eprintln!("Primero debes establecer la conexión gRPC de audio (/mic on o /listen on).");
            return Err("Conexión gRPC no activa".into());
        }

        // Obtener dispositivo de salida
        let host = cpal::default_host();
        let device = host
            .default_output_device()
            .ok_or("No se encontró dispositivo de salida")?;

        let config = device.default_output_config()?;

        // Crear stream de reproducción
        let stream = match config.sample_format() {
            cpal::SampleFormat::F32 => self.build_output_stream::<f32>(&device, &config.into())?,
            cpal::SampleFormat::I16 => self.build_output_stream::<i16>(&device, &config.into())?,
            cpal::SampleFormat::U16 => self.build_output_stream::<u16>(&device, &config.into())?,
            _ => return Err("Formato de muestra no soportado".into()),
        };

        stream.play()?;

        *self.speaker_stream.lock().unwrap() = Some(stream);
        *speakers_active = true;

        self.print_message("Altavoces activados para reproducción de audio.");
        Ok(())
    }

    fn build_output_stream<T>(
        &self,
        device: &Device,
        config: &StreamConfig,
    ) -> Result<Stream, Box<dyn std::error::Error>>
    where
        T: cpal::Sample,
    {
        let err_fn = |err| eprintln!("Error en el stream de audio: {}", err);

        let stream = device.build_output_stream(
            config,
            move |data: &mut [T], _: &cpal::OutputCallbackInfo| {
                // Aquí se debería reproducir el audio recibido del servidor
                // Por simplicidad, silenciamos la salida
                for sample in data.iter_mut() {
                    *sample = cpal::Sample::EQUILIBRIUM;
                }
            },
            err_fn,
            None,
        )?;

        Ok(stream)
    }

    pub fn stop_speakers(&mut self) {
        let mut speakers_active = self.speakers_active.lock().unwrap();
        if !*speakers_active {
            return;
        }

        *speakers_active = false;
        *self.speaker_stream.lock().unwrap() = None;

        self.print_message("Altavoces detenidos.");
    }

    pub fn is_mic_active(&self) -> bool {
        *self.mic_active.lock().unwrap()
    }

    pub fn is_speakers_active(&self) -> bool {
        *self.speakers_active.lock().unwrap()
    }

    pub fn is_grpc_stream_active(&self) -> bool {
        *self.grpc_stream_active.lock().unwrap()
    }
}
