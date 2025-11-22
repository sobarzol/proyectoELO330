#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/ipc.h>
#include <sys/shm.h>
#include <sys/sem.h>
#include <time.h>
#include <signal.h>

#define MAX_MESSAGES 50
#define MAX_MSG_LENGTH 256
#define MAX_USERNAME 32
#define SHM_KEY 0x1234
#define SEM_KEY 0x5678

// Estructura para un mensaje
typedef struct {
    char username[MAX_USERNAME];
    char text[MAX_MSG_LENGTH];
    time_t timestamp;
    int active;
} Message;

// Estructura de memoria compartida
typedef struct {
    Message messages[MAX_MESSAGES];
    int msg_count;
    int next_write_pos;
} SharedChat;

// Variables globales
int shmid = -1;
int semid = -1;
SharedChat *shared_chat = NULL;
char my_username[MAX_USERNAME];
int last_read_count = 0;

// Operaciones de semáforo
struct sembuf sem_lock = {0, -1, SEM_UNDO};
struct sembuf sem_unlock = {0, 1, SEM_UNDO};

void lock_semaphore() {
    semop(semid, &sem_lock, 1);
}

void unlock_semaphore() {
    semop(semid, &sem_unlock, 1);
}

// Limpieza al salir
void cleanup(int signum) {
    printf("\n\n¡Hasta luego, %s! 👋\n", my_username);
    
    if (shared_chat != NULL) {
        shmdt(shared_chat);
    }
    
    exit(0);
}

// Inicializar recursos compartidos
int init_shared_resources() {
    // Crear/obtener memoria compartida
    shmid = shmget(SHM_KEY, sizeof(SharedChat), IPC_CREAT | 0666);
    if (shmid < 0) {
        perror("Error al crear memoria compartida");
        return -1;
    }
    
    // Adjuntar memoria compartida
    shared_chat = (SharedChat *)shmat(shmid, NULL, 0);
    if (shared_chat == (void *)-1) {
        perror("Error al adjuntar memoria compartida");
        return -1;
    }
    
    // Crear/obtener semáforo
    semid = semget(SEM_KEY, 1, IPC_CREAT | 0666);
    if (semid < 0) {
        perror("Error al crear semáforo");
        return -1;
    }
    
    // Inicializar semáforo (solo la primera vez)
    semctl(semid, 0, SETVAL, 1);
    
    return 0;
}

// Enviar mensaje
void send_message(const char *text) {
    lock_semaphore();
    
    int pos = shared_chat->next_write_pos % MAX_MESSAGES;
    
    strncpy(shared_chat->messages[pos].username, my_username, MAX_USERNAME - 1);
    strncpy(shared_chat->messages[pos].text, text, MAX_MSG_LENGTH - 1);
    shared_chat->messages[pos].timestamp = time(NULL);
    shared_chat->messages[pos].active = 1;
    
    shared_chat->next_write_pos++;
    shared_chat->msg_count++;
    
    unlock_semaphore();
}

// Mostrar mensajes nuevos
void display_new_messages() {
    lock_semaphore();
    
    int current_count = shared_chat->msg_count;
    
    if (current_count > last_read_count) {
        // Calcular desde dónde empezar a leer
        int start = (current_count > MAX_MESSAGES) ? 
                    (current_count - MAX_MESSAGES) : 0;
        
        for (int i = last_read_count; i < current_count; i++) {
            int pos = i % MAX_MESSAGES;
            Message *msg = &shared_chat->messages[pos];
            
            if (msg->active) {
                struct tm *timeinfo = localtime(&msg->timestamp);
                char time_str[16];
                strftime(time_str, sizeof(time_str), "%H:%M:%S", timeinfo);
                
                // No mostrar nuestros propios mensajes (ya los vimos)
                if (strcmp(msg->username, my_username) != 0) {
                    printf("\r\033[K"); // Limpiar línea actual
                    printf("[%s] %s: %s\n", time_str, msg->username, msg->text);
                    printf("%s> ", my_username);
                    fflush(stdout);
                }
            }
        }
        
        last_read_count = current_count;
    }
    
    unlock_semaphore();
}

// Proceso hijo que escucha mensajes
void message_listener() {
    while (1) {
        display_new_messages();
        usleep(500000); // Revisar cada 0.5 segundos
    }
}

int main() {
    printf("=== CHAT MULTI-CONSOLA ===\n");
    
    // Inicializar recursos
    if (init_shared_resources() < 0) {
        fprintf(stderr, "Error al inicializar recursos compartidos\n");
        return 1;
    }
    
    // Configurar señal de salida
    signal(SIGINT, cleanup);
    
    // Solicitar nombre de usuario
    printf("Ingresa tu nombre de usuario: ");
    fgets(my_username, MAX_USERNAME, stdin);
    my_username[strcspn(my_username, "\n")] = 0;
    
    // Mensaje de bienvenida
    lock_semaphore();
    last_read_count = shared_chat->msg_count;
    unlock_semaphore();
    
    char welcome_msg[MAX_MSG_LENGTH];
    snprintf(welcome_msg, MAX_MSG_LENGTH, "*** se ha unido al chat ***");
    send_message(welcome_msg);
    
    printf("\n✓ Conectado al chat!\n");
    printf("Escribe tus mensajes (Ctrl+C para salir)\n\n");
    
    // Crear proceso hijo para escuchar mensajes
    pid_t pid = fork();
    
    if (pid < 0) {
        perror("Error al crear proceso hijo");
        cleanup(0);
        return 1;
    }
    
    if (pid == 0) {
        // Proceso hijo: escuchar mensajes
        message_listener();
        exit(0);
    } else {
        // Proceso padre: enviar mensajes
        char input[MAX_MSG_LENGTH];
        
        while (1) {
            printf("%s> ", my_username);
            fflush(stdout);
            
            if (fgets(input, MAX_MSG_LENGTH, stdin) == NULL) {
                break;
            }
            
            input[strcspn(input, "\n")] = 0;
            
            if (strlen(input) == 0) {
                continue;
            }
            
            if (strcmp(input, "/salir") == 0 || strcmp(input, "/exit") == 0) {
                break;
            }
            
            send_message(input);
        }
        
        // Mensaje de despedida
        snprintf(welcome_msg, MAX_MSG_LENGTH, "*** ha salido del chat ***");
        send_message(welcome_msg);
        
        kill(pid, SIGTERM);
        cleanup(0);
    }
    
    return 0;
}