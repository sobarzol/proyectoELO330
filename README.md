# Proyecto de Chat gRPC Multi-Lenguaje

Este proyecto implementa un sistema de chat de conferencia usando gRPC con streaming bidireccional. Incluye un servidor en Go y clientes en múltiples lenguajes de programación.

## 🎯 Inicio Rápido

Si quieres empezar rápidamente, lee `QUICKSTART.md` para una guía paso a paso.

Si necesitas instalar herramientas, consulta `INSTALL.md`.

## 📁 Estructura del Proyecto

```
proyecto/
├── chat-server/          # Servidor gRPC en Go
├── go-client/            # Cliente en Go
├── python-client/        # Cliente en Python
├── rust-client/          # Cliente en Rust
├── java-client/          # Cliente en Java
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

Puedes ejecutar cualquiera de los clientes disponibles:

```bash
# Cliente Go
make go-client

# Cliente Python
make python-client

# Cliente Rust
make rust-client

# Cliente Java
make java-client
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

### Para el Cliente Python

- **Python 3.8+**: [Descargar Python](https://www.python.org/downloads/)
- **Poetry** (gestor de dependencias moderno para Python): [Instalar Poetry](https://python-poetry.org/docs/#installation)
  ```bash
  curl -sSL https://install.python-poetry.org | python3 -
  ```
- Instalar dependencias del proyecto:
  ```bash
  cd python-client && poetry install
  ```

### Para el Cliente Rust

- **Rust**: [Instalar Rust](https://www.rust-lang.org/tools/install)
- **Cargo** (viene con Rust)

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

### 3. Cliente Python

```bash
cd python-client

# Instalar dependencias (si no lo hiciste antes)
poetry install

# Generar código proto
poetry run python -m grpc_tools.protoc -I. --python_out=. --grpc_python_out=. --pyi_out=. proto/chat.proto

# Ejecutar cliente
poetry run python chat_client.py
```

### 4. Cliente Rust

```bash
cd rust-client

# Compilar y ejecutar (cargo maneja la generación de proto automáticamente)
cargo run --release
```

### 5. Cliente Java

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
   make go-client      # o python-client, rust-client, java-client
   ```

4. **Ingresa tu nombre** cuando se te pida.

5. **Ingresa el ID de la sala** (por ejemplo: "sala1"). Los clientes en la misma sala podrán verse los mensajes entre sí.

6. **¡Empieza a chatear!** Escribe tus mensajes y presiona Enter. Verás los mensajes de otros usuarios en la misma sala.

## 🏗️ Arquitectura del Sistema

### Protocolo gRPC

El sistema usa **streaming bidireccional** de gRPC, definido en el archivo `.proto`:

```protobuf
service ChatService {
  rpc JoinChatRoom(stream ChatMessage) returns (stream ChatMessage);
}
```

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

### Error: "No module named 'grpc'" en Python
Instala las dependencias: `pip install -r python-client/requirements.txt`

### Error de conexión al servidor
Asegúrate de que:
1. El servidor esté ejecutándose
2. No haya un firewall bloqueando el puerto 50051
3. El servidor esté escuchando en el puerto correcto

### El cliente Java no compila
Verifica que tienes Maven instalado: `mvn --version`

## 📚 Recursos Adicionales

- [Documentación oficial de gRPC](https://grpc.io/docs/)
- [Tutorial de gRPC en Go](https://grpc.io/docs/languages/go/quickstart/)
- [Tutorial de gRPC en Python](https://grpc.io/docs/languages/python/quickstart/)
- [Tutorial de gRPC en Rust](https://github.com/hyperium/tonic)
- [Tutorial de gRPC en Java](https://grpc.io/docs/languages/java/quickstart/)

## 📝 Notas de Implementación

- **Concurrencia**: El servidor maneja múltiples clientes usando goroutines (Go)
- **Salas**: Los clientes pueden unirse a diferentes salas especificando el `room_id`
- **Timestamps**: Todos los mensajes incluyen timestamps Unix para mostrar la hora
- **Desconexión**: El servidor detecta y maneja automáticamente cuando un cliente se desconecta

## 🎯 Características

- ✅ Streaming bidireccional en tiempo real
- ✅ Soporte para múltiples salas de chat
- ✅ Múltiples clientes simultáneos
- ✅ Implementaciones en 4 lenguajes diferentes
- ✅ Makefile para facilitar el desarrollo
- ✅ Manejo robusto de errores y desconexiones
- ✅ Timestamps en los mensajes

## 🤝 Contribución

Este es un proyecto educativo para el curso ELO330. Si encuentras algún problema o tienes sugerencias, no dudes en reportarlo.

## 📄 Licencia

Este proyecto es parte del curso ELO330 de la Universidad Santa María.

---

**¡Disfruta chateando con gRPC!** 🎉
