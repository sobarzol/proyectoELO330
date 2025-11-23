#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <time.h>
#include <signal.h>

#define MAX_MESSAGE_SIZE 1024
#define MAX_NAME_SIZE 256

// Estructura simple para simular mensajes gRPC
typedef struct {
    char sender[MAX_NAME_SIZE];
    char message[MAX_MESSAGE_SIZE];
    char room_id[MAX_NAME_SIZE];
    long timestamp;
    char trace_id[64];
} ChatMessage;

typedef struct {
    char sender[MAX_NAME_SIZE];
    char room_id[MAX_NAME_SIZE];
    int running;
    pthread_t receive_thread;
} chat_client_t;

static volatile int keep_running = 1;

void signal_handler(int signum) {
    (void)signum;
    keep_running = 0;
}

void get_current_time(char *buffer, size_t size) {
    time_t now = time(NULL);
    struct tm *t = localtime(&now);
    snprintf(buffer, size, "%02d:%02d", t->tm_hour, t->tm_min);
}

void print_header() {
    printf("\n🔌 Conectando al servidor...\n");
}

void print_room_header() {
    printf("\n");
    for (int i = 0; i < 50; i++) printf("━");
    printf("\n");
    printf("           UNIRSE A UNA SALA DE CHAT\n");
    for (int i = 0; i < 50; i++) printf("━");
    printf("\n\n");
}

void get_input(const char *prompt, char *buffer, size_t size) {
    printf("%s", prompt);
    fflush(stdout);
    if (fgets(buffer, size, stdin) != NULL) {
        size_t len = strlen(buffer);
        if (len > 0 && buffer[len-1] == '\n') {
            buffer[len-1] = '\0';
        }
    }
}

char* trim(char *str) {
    char *end;
    while(*str == ' ') str++;
    if(*str == 0) return str;
    end = str + strlen(str) - 1;
    while(end > str && *end == ' ') end--;
    end[1] = '\0';
    return str;
}

// Simular recepción de mensajes (en una implementación real, esto vendría del servidor gRPC)
void* receive_messages(void *arg) {
    chat_client_t *client = (chat_client_t*)arg;
    char time_str[16];
    
    // Simular mensaje de bienvenida del servidor
    sleep(1);
    get_current_time(time_str, sizeof(time_str));
    printf("\r\033[2K[%s] Servidor: Bienvenido a la sala '%s', %s\n", 
           time_str, client->room_id, client->sender);
    printf("[%s] Tú: ", time_str);
    fflush(stdout);
    
    return NULL;
}

int validate_name(const char *sender, const char *room_id) {
    // En una implementación real, esto enviaría un mensaje al servidor
    // y esperaría la respuesta de validación
    // Por ahora, simulamos que todos los nombres son válidos
    (void)sender;
    (void)room_id;
    return 1; // 1 = nombre válido, 0 = nombre duplicado
}

void send_message(chat_client_t *client, const char *message) {
    // En una implementación real, esto enviaría el mensaje vía gRPC
    // Por ahora, solo lo mostramos localmente
    (void)client;
    (void)message;
    // printf("(Mensaje enviado al servidor: %s)\n", message);
}

int main(int argc, char **argv) {
    (void)argc;
    (void)argv;
    
    char server_addr[256] = "localhost";
    char server_port[10] = "50051";
    char room_id[MAX_NAME_SIZE];
    char sender[MAX_NAME_SIZE];
    char full_address[512];
    char message[MAX_MESSAGE_SIZE];
    char time_str[16];
    
    signal(SIGINT, signal_handler);
    
    // Pedir dirección del servidor
    get_input("Dirección del servidor [localhost]: ", server_addr, sizeof(server_addr));
    if (strlen(server_addr) == 0) {
        strcpy(server_addr, "localhost");
    }
    
    get_input("Puerto del servidor [50051]: ", server_port, sizeof(server_port));
    if (strlen(server_port) == 0) {
        strcpy(server_port, "50051");
    }
    
    snprintf(full_address, sizeof(full_address), "%s:%s", server_addr, server_port);
    printf("\n🔌 Conectando a %s...\n", full_address);
    
    // Simular conexión exitosa
    sleep(1);
    printf("✅ Conectado al servidor exitosamente\n");
    
    // Mostrar header de sala
    print_room_header();
    
    // Pedir ID de sala
    get_input("🏠 ID de la sala (ej: 1, sala1, proyecto): ", room_id, sizeof(room_id));
    
    if (strlen(room_id) == 0) {
        fprintf(stderr, "¡El ID de la sala no puede estar vacío!\n");
        return 1;
    }
    
    // Loop para obtener nombre válido
    while (keep_running) {
        get_input("👤 Tu nombre de usuario: ", sender, sizeof(sender));
        
        if (strlen(sender) == 0) {
            printf("El nombre no puede estar vacío. Intenta de nuevo.\n\n");
            continue;
        }
        
        // Validar nombre con el servidor
        if (validate_name(sender, room_id)) {
            printf("✅ Conectado exitosamente como '%s' en sala '%s'\n\n", sender, room_id);
            break;
        } else {
            printf("\n❌ El nombre '%s' ya está en uso en esta sala.\n", sender);
            printf("Por favor, elige otro nombre.\n\n");
        }
    }
    
    if (!keep_running) {
        return 0;
    }
    
    // Inicializar cliente
    chat_client_t client;
    strncpy(client.sender, sender, MAX_NAME_SIZE - 1);
    strncpy(client.room_id, room_id, MAX_NAME_SIZE - 1);
    client.running = 1;
    
    // Crear thread para recibir mensajes
    pthread_create(&client.receive_thread, NULL, receive_messages, &client);
    
    printf("Ya puedes chatear. Escribe tu mensaje y presiona Enter.\n");
    printf("Escribe /quit para salir.\n\n");
    
    // Loop principal de chat
    while (keep_running && client.running) {
        get_current_time(time_str, sizeof(time_str));
        printf("[%s] Tú: ", time_str);
        fflush(stdout);
        
        if (fgets(message, sizeof(message), stdin) == NULL) {
            break;
        }
        
        // Remover newline
        size_t len = strlen(message);
        if (len > 0 && message[len-1] == '\n') {
            message[len-1] = '\0';
        }
        
        // Trim espacios
        char *trimmed = trim(message);
        
        if (strlen(trimmed) == 0) {
            continue;
        }
        
        if (strcmp(trimmed, "/quit") == 0 || 
            strcmp(trimmed, "/exit") == 0 || 
            strcmp(trimmed, "/disconnect") == 0) {
            printf("\nSaliendo del chat...\n");
            client.running = 0;
            break;
        } else if (strcmp(trimmed, "/mic on") == 0) {
            printf("🎤 Micrófono activado\n");
            // En implementación real: activar captura de audio
            continue;
        } else if (strcmp(trimmed, "/mic off") == 0) {
            printf("🎤 Micrófono desactivado\n");
            // En implementación real: desactivar captura de audio
            continue;
        } else if (strcmp(trimmed, "/listen on") == 0) {
            printf("🔊 Altavoces activados\n");
            // En implementación real: activar reproducción de audio
            continue;
        } else if (strcmp(trimmed, "/listen off") == 0) {
            printf("🔊 Altavoces desactivados\n");
            // En implementación real: desactivar reproducción de audio
            continue;
        } else if (strcmp(trimmed, "/help") == 0) {
            printf("\n═══════════════════════════════════════════════════════\n");
            printf("           COMANDOS DISPONIBLES\n");
            printf("═══════════════════════════════════════════════════════\n");
            printf("\n📝 Comandos de Chat:\n");
            printf("  /help                          - Mostrar esta ayuda\n");
            printf("  /quit, /exit, /disconnect      - Salir del chat\n");
            printf("\n🎤 Comandos de Audio:\n");
            printf("  /mic on                        - Activar micrófono\n");
            printf("  /mic off                       - Desactivar micrófono\n");
            printf("  /listen on                     - Activar altavoces\n");
            printf("  /listen off                    - Desactivar altavoces\n");
            printf("\n═══════════════════════════════════════════════════════\n\n");
            continue;
        }
        
        // Enviar mensaje al servidor
        send_message(&client, trimmed);
    }
    
    // Cleanup
    client.running = 0;
    pthread_join(client.receive_thread, NULL);
    
    return 0;
}
