# Proyecto de Chat gRPC con Streaming de Audio

Este proyecto implementa un sistema de chat de conferencia usando gRPC con streaming bidireccional, incluyendo streaming de audio en tiempo real. Incluye un servidor en Go y clientes en Go y Java.

## 🎯 Inicio Rápido

## 🎯 Características

- ✅ Streaming bidireccional de mensajes en tiempo real
- ✅ **Streaming de audio bidireccional** con PortAudio
- ✅ Transferencia de archivos entre usuarios
- ✅ Soporte para múltiples salas de chat
- ✅ Múltiples clientes simultáneos
- ✅ Compilación multiplataforma (Linux, macOS, Windows)
- ✅ JAR ejecutable standalone para Java
- ✅ Build tags para soporte multiplataforma en Go

## 📁 Estructura del Proyecto

```
proyecto/
├── chat-server/          # Servidor gRPC en Go
├── go-client/            # Cliente en Go con soporte de audio
│   ├── audio_streamer_unix.go    # Implementación audio para Linux/macOS
│   └── audio_streamer_windows.go # Implementación audio para Windows
├── java-client/          # Cliente en Java con soporte de audio
├── python-client/        # Cliente en Python (legacy)
├── c-client/             # Cliente en C (legacy)
├── Makefile              # Makefile general para gestionar todo el proyecto
└── README.md             # Este archivo
```

## 🚀 Inicio Rápido con Makefile

El proyecto incluye un **Makefile** completo que facilita todas las operaciones. Para ver todos los comandos disponibles:

```bash
make help
```

### Instalación de Dependencias

Para instalar todas las dependencias necesarias:

```bash
make install-deps
```

### Ejecutar el Servidor

```bash
make server
```

El servidor escuchará en el puerto **50051**.

### Ejecutar un Cliente

Puedes ejecutar los clientes disponibles:

```bash
# Cliente Go
make go-client

# Cliente Java
make java-client
```

### Compilar para Windows (Go)

```bash
# Cross-compilar cliente Go para Windows desde Linux/macOS
make go-client-build-windows
```

### Crear JAR Ejecutable (Java)

```bash
# Compilar JAR con todas las dependencias
make java-client-jar

# Ejecutar el JAR
java -jar java-client/target/chat-client-1.0-SNAPSHOT-jar-with-dependencies.jar
```

### Generar Código Protobuf

Para generar código protobuf para todos los proyectos:

```bash
make proto
```

### Compilar Todo

Para compilar todos los proyectos:

```bash
make build
```

### Limpiar Archivos Generados

```bash
make clean
```

## 📋 Prerrequisitos

Antes de comenzar, asegúrate de tener instalado:

### Requisitos Generales

- **protoc** (Protocol Buffers Compiler): [Instrucciones de instalación](https://grpc.io/docs/protoc-installation/)

### Para el Servidor y Cliente Go

- **Go 1.23+**: [Guía de instalación](https://golang.org/doc/install)
- **Plugins de protoc para Go:**
  ```bash
  go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
  go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest
  ```
  Asegúrate de que `$(go env GOPATH)/bin` esté en tu `PATH`.

### Para Soporte de Audio

**PortAudio** (requerido para Go y Java):
- **Linux (Debian/Ubuntu)**:
  ```bash
  sudo apt-get install portaudio19-dev
  ```
- **Linux (Arch)**:
  ```bash
  sudo pacman -S portaudio
  ```
- **macOS**:
  ```bash
  brew install portaudio
  ```
- **Windows**: Las DLLs de PortAudio deben estar en el PATH

### Para Cross-Compilation a Windows (Go)

**MinGW-w64** (solo si compilas desde Linux/macOS):
```bash
sudo apt-get install mingw-w64  # Debian/Ubuntu
sudo pacman -S mingw-w64-gcc    # Arch Linux
```

### Para el Cliente Java

- **Java 11+**: [Descargar Java](https://www.oracle.com/java/technologies/downloads/)
- **Maven**: [Instalar Maven](https://maven.apache.org/install.html)

## 🔧 Uso Manual (sin Makefile)

Si prefieres ejecutar los comandos manualmente:

### 1. Servidor (Go)

```bash
cd chat-server

# Generar código proto
protoc --go_out=. --go_opt=paths=source_relative \
    --go-grpc_out=. --go-grpc_opt=paths=source_relative \
    chat/chat.proto

# Descargar dependencias
go mod tidy

# Ejecutar servidor
go run main.go
```

### 2. Cliente Go

```bash
cd go-client

# Generar código proto
protoc --go_out=. --go_opt=paths=source_relative \
    --go-grpc_out=. --go-grpc_opt=paths=source_relative \
    chat/chat.proto

# Descargar dependencias
go mod tidy

# Ejecutar cliente
go run main.go
```

### 3. Cliente Java

```bash
cd java-client

# Generar código proto y compilar
mvn clean compile

# Ejecutar cliente
mvn exec:java
```

## 💬 Cómo Usar el Chat

1. **Inicia el servidor** primero (en una terminal):
   ```bash
   make server
   ```

2. **Abre múltiples terminales** para los clientes que quieras probar.

3. **Ejecuta un cliente** en cada terminal:
   ```bash
   make go-client      # o java-client
   ```

4. **Ingresa tu nombre** cuando se te pida.

5. **Ingresa el ID de la sala** (por ejemplo: "sala1"). Los clientes en la misma sala podrán verse los mensajes entre sí.

6. **¡Empieza a chatear!** Escribe tus mensajes y presiona Enter. Verás los mensajes de otros usuarios en la misma sala.

### Comandos del Chat

#### Comandos de Texto
- Escribe cualquier mensaje y presiona Enter para enviarlo
- `/quit`, `/exit`, `/disconnect` - Salir del chat

#### Comandos de Audio
- `/mic on` - Activar micrófono y altavoces (hablar y escuchar)
- `/mic off` - Desactivar micrófono y altavoces
- `/listen on` - Activar solo altavoces (escuchar sin transmitir)
- `/listen off` - Desactivar altavoces

## 🏗️ Arquitectura del Sistema

### Protocolo gRPC

El sistema usa **streaming bidireccional** de gRPC, definido en el archivo `.proto`:

```protobuf
service ChatService {
  rpc JoinChatRoom(stream ChatMessage) returns (stream ChatMessage);
  rpc StreamAudio(stream AudioChunk) returns (stream AudioChunk);
  rpc TransferFile(stream FileChunk) returns (FileTransferResponse);
}
```

### Streaming de Audio

El audio se transmite en tiempo real usando gRPC bidirectional streaming:
- **Sample Rate**: 44.1 kHz
- **Canales**: Mono (1 canal)
- **Profundidad**: 16 bits
- **Buffer**: 1024 frames

#### Implementación Multiplataforma

El cliente Go utiliza **build tags** para soporte multiplataforma:
- `//go:build !windows` - `audio_streamer_unix.go` para Linux y macOS
- `//go:build windows` - `audio_streamer_windows.go` para Windows

Ambos utilizan la biblioteca **PortAudio** para captura y reproducción de audio.

### Flujo de Comunicación

1. El cliente se conecta al servidor y envía un mensaje inicial para unirse a una sala.
2. El servidor mantiene una lista de clientes conectados por sala.
3. Cuando un cliente envía un mensaje:
   - El servidor lo recibe a través del stream de entrada
   - El servidor reenvía el mensaje a todos los demás clientes en la misma sala
   - Los clientes reciben el mensaje a través de su stream de salida

### Servidor (Go)

- Mantiene un mapa de salas (`rooms`) donde cada sala contiene una lista de conexiones activas
- Usa goroutines para manejar múltiples clientes concurrentemente
- Gestiona automáticamente la adición y eliminación de clientes

### Clientes

Todos los clientes implementan la misma lógica básica:
- Conexión al servidor en `localhost:50051`
- Stream bidireccional para enviar y recibir mensajes
- Threads/tareas separadas para lectura de entrada del usuario y recepción de mensajes del servidor

## 🐛 Solución de Problemas

### Error: "protoc: command not found"
Instala el compilador de Protocol Buffers siguiendo las [instrucciones oficiales](https://grpc.io/docs/protoc-installation/).

### Error: "cannot find package" en Go
Ejecuta `go mod tidy` en el directorio del servidor o cliente Go.

### Error: "PortAudio no encontrado"
- **Linux**: `sudo apt-get install portaudio19-dev`
- **macOS**: `brew install portaudio`
- **Windows**: Descargar DLLs de PortAudio desde el sitio oficial

### Error de conexión al servidor
Asegúrate de que:
1. El servidor esté ejecutándose
2. No haya un firewall bloqueando el puerto 50051
3. El servidor esté escuchando en el puerto correcto

### El cliente Java no compila
Verifica que tienes Maven instalado: `mvn --version`

### Error al compilar para Windows desde Linux
Instala MinGW-w64:
```bash
sudo apt-get install mingw-w64  # Debian/Ubuntu
```

### JAR no se ejecuta
Verifica que tienes Java 11+:
```bash
java -version
```

## 📚 Recursos Adicionales

- [Documentación oficial de gRPC](https://grpc.io/docs/)
- [Tutorial de gRPC en Go](https://grpc.io/docs/languages/go/quickstart/)
- [Tutorial de gRPC en Java](https://grpc.io/docs/languages/java/quickstart/)
- [Documentación de PortAudio](http://www.portaudio.com/)

## 📝 Notas de Implementación

- **Concurrencia**: El servidor maneja múltiples clientes usando goroutines (Go)
- **Salas**: Los clientes pueden unirse a diferentes salas especificando el `room_id`
- **Timestamps**: Todos los mensajes incluyen timestamps Unix para mostrar la hora
- **Desconexión**: El servidor detecta y maneja automáticamente cuando un cliente se desconecta

## 📦 Distribución de Binarios

### Cliente Go para Windows

Para distribuir el cliente Go en Windows:

1. Compilar desde Linux/macOS:
   ```bash
   make go-client-build-windows
   ```

2. Distribuir `go-client/client.exe` junto con las DLLs de PortAudio

### Cliente Java (Multiplataforma)

El JAR con dependencias funciona en cualquier sistema con Java 11+:

```bash
java -jar java-client/target/chat-client-1.0-SNAPSHOT-jar-with-dependencies.jar
```

## 🤝 Contribución

Este es un proyecto educativo para el curso ELO330. Si encuentras algún problema o tienes sugerencias, no dudes en reportarlo.

## 📄 Licencia

Este proyecto es parte del curso ELO330 de la Universidad Santa María.

---

**¡Disfruta chateando con gRPC!** 🎉
