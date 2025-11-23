use tokio::sync::mpsc;
use tokio_stream::wrappers::ReceiverStream;
use tonic::transport::Channel;
use uuid::Uuid;
use std::io::{self, BufRead, Write};
use std::time::{SystemTime, UNIX_EPOCH};

// Importar el código generado por tonic-build
pub mod chat {
    tonic::include_proto!("chat");
}
use chat::{chat_service_client::ChatServiceClient, ChatMessage};

mod audio_streamer;
use audio_streamer::AudioStreamer;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    // Conectar al servidor
    let channel = Channel::from_static("http://[::1]:50051")
        .connect()
        .await?;

    let mut client = ChatServiceClient::new(channel);

    // Pedir nombre de usuario y sala
    print!("Ingresa tu nombre: ");
    io::stdout().flush().unwrap();
    let sender = read_line_from_stdin();

    print!("Ingresa el ID de la sala: ");
    io::stdout().flush().unwrap();
    let room_id = read_line_from_stdin();

    // Crear AudioStreamer
    let mut audio_streamer = AudioStreamer::new(client.clone(), sender.clone(), room_id.clone());

    // Crear un canal para pasar los mensajes del usuario desde stdin al stream gRPC
    let (tx, rx) = mpsc::channel(32);

    // Enviar el mensaje inicial de "unión" antes de mover tx
    let join_message = ChatMessage {
        sender: sender.clone(),
        message: format!("{} se ha unido a la sala.", sender),
        room_id: room_id.clone(),
        timestamp: SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_secs() as i64,
        trace_id: Uuid::new_v4().to_string(),
    };
    tx.send(join_message).await?;

    // Hilo para leer la entrada del usuario en un hilo de bloqueo para no congelar el runtime de tokio
    let sender_clone = sender.clone();
    let room_id_clone = room_id.clone();
    tokio::task::spawn_blocking(move || {
        println!("Ya puedes chatear. Escribe tu mensaje y presiona Enter.");
        println!("Usa /mic on para enviar tu voz, /mic off para silenciarte.");
        println!("Usa /listen on para escuchar, /listen off para dejar de escuchar.");
        println!("El audio se activará automáticamente con /mic on o /listen on.");

        loop {
            let current_time = chrono::Local::now().format("%H:%M");
            print!("[{}] Tú: ", current_time);
            io::stdout().flush().unwrap();

            let message = read_line_from_stdin();
            if message.is_empty() {
                continue;
            }

            // Command handling
            if message == "/quit" || message == "/exit" || message == "/disconnect" {
                println!("Saliendo del chat...");
                // The channel will be dropped, implicitly notifying the server
                break;
            } else if message == "/mic on" {
                // Audio commands are handled in the main task
                // Send a special message to trigger audio
                let chat_message = ChatMessage {
                    sender: sender_clone.clone(),
                    message: "/mic on".to_string(),
                    room_id: room_id_clone.clone(),
                    timestamp: 0,
                    trace_id: "".to_string(),
                };
                if tx.blocking_send(chat_message).is_err() {
                    break;
                }
                continue;
            } else if message == "/mic off" {
                let chat_message = ChatMessage {
                    sender: sender_clone.clone(),
                    message: "/mic off".to_string(),
                    room_id: room_id_clone.clone(),
                    timestamp: 0,
                    trace_id: "".to_string(),
                };
                if tx.blocking_send(chat_message).is_err() {
                    break;
                }
                continue;
            } else if message == "/listen on" {
                let chat_message = ChatMessage {
                    sender: sender_clone.clone(),
                    message: "/listen on".to_string(),
                    room_id: room_id_clone.clone(),
                    timestamp: 0,
                    trace_id: "".to_string(),
                };
                if tx.blocking_send(chat_message).is_err() {
                    break;
                }
                continue;
            } else if message == "/listen off" {
                let chat_message = ChatMessage {
                    sender: sender_clone.clone(),
                    message: "/listen off".to_string(),
                    room_id: room_id_clone.clone(),
                    timestamp: 0,
                    trace_id: "".to_string(),
                };
                if tx.blocking_send(chat_message).is_err() {
                    break;
                }
                continue;
            }

            let timestamp = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_secs() as i64;

            let chat_message = ChatMessage {
                sender: sender_clone.clone(),
                message,
                room_id: room_id_clone.clone(),
                timestamp,
                trace_id: Uuid::new_v4().to_string(),
            };

            if tx.blocking_send(chat_message).is_err() {
                // This will happen if the receiver (main task) terminates.
                break;
            }
        }
    });

    // Crear el stream gRPC bidireccional
    let request_stream = ReceiverStream::new(rx);
    let mut response_stream = client.join_chat_room(request_stream).await?.into_inner();


    // Recibir mensajes del servidor
    while let Some(received) = response_stream.message().await? {
        // Imprimir si no es del mismo sender
        if received.sender != sender {
			println!("\n[TraceID: {}]", received.trace_id);
            let timestamp = chrono::DateTime::from_timestamp(received.timestamp, 0)
                .unwrap_or_default()
                .with_timezone(&chrono::Local)
                .format("%H:%M");
            
            let current_time = chrono::Local::now().format("%H:%M");

            // Limpiar la línea actual, imprimir el mensaje y re-imprimir el prompt del usuario
            print!("\r\x1b[2K"); // Mover cursor al inicio y limpiar línea
            println!("[{}] {}: {}", timestamp, received.sender, received.message);
            print!("[{}] Tú: ", current_time); // Re-imprimir prompt
            io::stdout().flush().unwrap();
        }
    }

    Ok(())
}

fn read_line_from_stdin() -> String {
    let mut input = String.new();
    let stdin = io::stdin();
    let mut handle = stdin.lock();
    handle.read_line(&mut input).expect("Error al leer la línea");
    input.trim().to_string()
}