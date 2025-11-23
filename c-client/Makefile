CC = gcc
CFLAGS = -Wall -Wextra -pthread -std=c11
LDFLAGS = -lpthread

# Detectar si portaudio está disponible
PORTAUDIO_EXISTS := $(shell pkg-config --exists portaudio-2.0 && echo yes)

ifeq ($(PORTAUDIO_EXISTS),yes)
    CFLAGS += -DHAVE_PORTAUDIO $(shell pkg-config --cflags portaudio-2.0)
    LDFLAGS += $(shell pkg-config --libs portaudio-2.0)
endif

TARGET = client
SRCS = chat_client.c
OBJS = $(SRCS:.c=.o)

all: $(TARGET)

$(TARGET): $(OBJS)
	$(CC) $(OBJS) -o $(TARGET) $(LDFLAGS)

%.o: %.c
	$(CC) $(CFLAGS) -c $< -o $@

clean:
	rm -f $(OBJS) $(TARGET)

run: $(TARGET)
	./$(TARGET)

.PHONY: all clean run
