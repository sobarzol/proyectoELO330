# Inicio Rápido - Guía de Prueba del Chat

Esta guía te llevará paso a paso para probar el sistema de chat gRPC.

## ✅ Pre-requisitos Mínimos

Para empezar a probar el chat necesitas tener instalado:

```bash
make check-tools
```

**Mínimo necesario:**
- ✓ Go
- ✓ protoc
- ✗ protoc-gen-go (instalar)
- ✗ protoc-gen-go-grpc (instalar)
- ✓ Poetry

## 🚀 Instalación Rápida

### 1. Instalar plugins de Go para protobuf

```bash
go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest
```

### 2. Agregar al PATH

Agrega esto a tu `~/.zshrc` (o `~/.bashrc` si usas bash):

```bash
export PATH="$PATH:$(go env GOPATH)/bin"
```

Luego recarga:

```bash
source ~/.zshrc  # o source ~/.bashrc
```

### 3. Instalar dependencias del proyecto

```bash
cd ~/USM/2025-2/ELO330/proyecto
make install-deps
```

## 🎮 Probando el Chat

### Opción 1: Servidor Go + Cliente Python

**Terminal 1 - Servidor:**
```bash
cd ~/USM/2025-2/ELO330/proyecto
make server
```

Deberías ver:
```
Generating server protobuf code...
Server proto files generated!
Building server...
Server built successfully!
Starting server on port 50051...
server listening at [::]:50051
```

**Terminal 2 - Cliente Python:**
```bash
cd ~/USM/2025-2/ELO330/proyecto
make python-client
```

Te pedirá:
- **Nombre:** Escribe tu nombre (ej: "Carlos")
- **Room ID:** Escribe un ID de sala (ej: "sala1")

**Terminal 3 - Otro Cliente Python:**
```bash
cd ~/USM/2025-2/ELO330/proyecto
make python-client
```

Te pedirá:
- **Nombre:** Escribe otro nombre (ej: "Ana")
- **Room ID:** Escribe el mismo ID (ej: "sala1")

¡Ahora puedes chatear entre ambas terminales! 🎉

### Opción 2: Servidor Go + Cliente Go

**Terminal 1 - Servidor:**
```bash
make server
```

**Terminal 2 - Cliente Go:**
```bash
make go-client
```

### Opción 3: Servidor Go + Cliente Rust

**Terminal 1 - Servidor:**
```bash
make server
```

**Terminal 2 - Cliente Rust:**
```bash
make rust-client
```

### Opción 4: Mix de Clientes

¡Puedes mezclar clientes! Por ejemplo:

- **Terminal 1:** Servidor Go
- **Terminal 2:** Cliente Python
- **Terminal 3:** Cliente Go
- **Terminal 4:** Cliente Rust

Todos en la misma sala podrán chatear entre sí, independientemente del lenguaje del cliente.

## 🧪 Ejemplo de Sesión de Chat

### Terminal 1 (Servidor):
```
server listening at [::]:50051
2025/11/22 21:00:00 Received join request from Carlos for room sala1
2025/11/22 21:00:05 Received join request from Ana for room sala1
2025/11/22 21:00:10 Received message from Carlos in room sala1: Hola!
2025/11/22 21:00:15 Received message from Ana in room sala1: Hola Carlos!
```

### Terminal 2 (Cliente Python - Carlos):
```
Enter your name: Carlos
Enter room ID: sala1
You can now start chatting. Type your message and press Enter.
[21:00] You: Hola!
[21:00] Ana: Hola Carlos!
[21:00] You: ¿Cómo estás?
```

### Terminal 3 (Cliente Go - Ana):
```
Enter your name: Ana
Enter room ID: sala1
You can now start chatting. Type your message and press Enter.
[21:00] You: Hola Carlos!
[21:00] Carlos: Hola!
[21:00] Carlos: ¿Cómo estás?
[21:00] You: Muy bien, gracias!
```

## 📋 Comandos Útiles

### Ver ayuda
```bash
make help
```

### Verificar herramientas
```bash
make check-tools
```

### Limpiar archivos generados
```bash
make clean
```

### Generar solo archivos protobuf
```bash
make proto
```

### Compilar todo sin ejecutar
```bash
make build
```

## 🐛 Solución de Problemas Comunes

### Error: "protoc-gen-go: program not found"

Significa que no instalaste los plugins de Go o no están en el PATH.

**Solución:**
```bash
go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest
export PATH="$PATH:$(go env GOPATH)/bin"
```

### Error: "could not connect to server"

El servidor no está ejecutándose.

**Solución:**
```bash
# En una terminal separada, ejecuta:
make server
```

### Error: "address already in use"

Ya hay un servidor ejecutándose en el puerto 50051.

**Solución:**
```bash
# Mata el proceso anterior:
pkill -f "chat-server"
# O encuentra el proceso:
lsof -i :50051
kill -9 <PID>
```

### Los clientes no se ven entre sí

Asegúrate de que estén en la misma sala (mismo `room_id`).

## 🎯 Características para Probar

1. **Múltiples salas:** Crea clientes en diferentes salas y verifica que solo los de la misma sala se vean
2. **Múltiples clientes:** Abre 5+ clientes en la misma sala
3. **Desconexión:** Cierra un cliente (Ctrl+C) y verifica que los demás sigan funcionando
4. **Mix de lenguajes:** Prueba Python, Go y Rust en la misma sala
5. **Mensajes largos:** Envía mensajes largos y verifica que se transmitan correctamente
6. **Caracteres especiales:** Prueba con emojis, tildes, ñ, etc.

## 📚 Próximos Pasos

- Lee `README.md` para documentación completa
- Revisa `INSTALL.md` para instalación detallada de herramientas
- Explora el código en cada directorio de cliente y servidor
- Modifica y experimenta con el código

¡Disfruta chateando con gRPC! 🚀
