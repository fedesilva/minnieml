#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

// --- String Struct ---
typedef struct
{
    size_t length;
    char *data;
} String;

// --- Read a line from stdin ---
String readline()
{
    size_t size = 1024;
    char *buffer = (char *)malloc(size);
    if (!buffer)
        return (String){0, NULL};
    if (fgets(buffer, size, stdin))
    {
        buffer[strcspn(buffer, "\n")] = 0;
        return (String){strlen(buffer), buffer};
    }
    free(buffer);
    return (String){0, NULL};
}

// --- Print a string (no newline) ---
void print(String str)
{
    if (str.data)
        write(STDOUT_FILENO, str.data, str.length);
}

// --- Print a string with newline ---
void println(String str)
{
    if (str.data)
    {
        struct iovec iov[2];
        iov[0].iov_base = str.data;
        iov[0].iov_len = str.length;
        iov[1].iov_base = "\n";
        iov[1].iov_len = 1;
        writev(STDOUT_FILENO, iov, 2);
    }
}

// --- StringBuilder ---
typedef struct
{
    size_t capacity;
    size_t length;
    char *buffer;
} StringBuilder;

StringBuilder *string_builder_new(size_t initial_capacity)
{
    StringBuilder *sb = (StringBuilder *)malloc(sizeof(StringBuilder));
    if (!sb)
        return NULL;

    sb->buffer = (char *)malloc(initial_capacity);
    if (!sb->buffer)
    {
        free(sb);
        return NULL;
    }

    sb->capacity = initial_capacity;
    sb->length = 0;
    return sb;
}

void string_builder_append(StringBuilder *sb, String str)
{
    if (!sb || !str.data)
        return;

    if (sb->length + str.length >= sb->capacity)
    {
        sb->capacity *= 2;
        char *new_buffer = (char *)realloc(sb->buffer, sb->capacity);
        if (!new_buffer)
            return;
        sb->buffer = new_buffer;
    }
    memcpy(sb->buffer + sb->length, str.data, str.length);
    sb->length += str.length;
    sb->buffer[sb->length] = '\0';
}

String string_builder_finalize(StringBuilder *sb)
{
    if (!sb)
        return (String){0, NULL};

    char *data = (char *)malloc(sb->length + 1);
    if (!data)
    {
        free(sb->buffer);
        free(sb);
        return (String){0, NULL};
    }

    String result = {sb->length, data};
    memcpy(result.data, sb->buffer, sb->length);
    result.data[result.length] = '\0';
    free(sb->buffer);
    free(sb);
    return result;
}

// --- Output Buffer ---
typedef struct
{
    size_t capacity;
    size_t length;
    char *data;
    int fd;
} BufferImpl;

typedef BufferImpl *Buffer;

Buffer mkBuffer()
{
    Buffer b = (Buffer)malloc(sizeof(BufferImpl));
    if (!b)
        return NULL;
    b->capacity = 4096;
    b->length = 0;
    b->fd = STDOUT_FILENO;
    b->data = (char *)malloc(b->capacity);
    if (!b->data)
    {
        free(b);
        return NULL;
    }
    return b;
}

Buffer mkBufferWithFd(int fd)
{
    Buffer b = (Buffer)malloc(sizeof(BufferImpl));
    if (!b)
        return NULL;
    b->capacity = 4096;
    b->length = 0;
    b->fd = fd;
    b->data = (char *)malloc(b->capacity);
    if (!b->data)
    {
        free(b);
        return NULL;
    }
    return b;
}

Buffer mkBufferWithSize(int64_t size)
{
    Buffer b = (Buffer)malloc(sizeof(BufferImpl));
    if (!b)
        return NULL;
    b->capacity = size > 0 ? (size_t)size : 4096;
    b->length = 0;
    b->fd = STDOUT_FILENO;
    b->data = (char *)malloc(b->capacity);
    if (!b->data)
    {
        free(b);
        return NULL;
    }
    return b;
}

void flush(Buffer b)
{
    if (b && b->data && b->length > 0)
    {
        write(b->fd, b->data, b->length);
        b->length = 0;
    }
}

void buffer_write(Buffer b, String s)
{
    if (!b || !s.data)
        return;
    while (b->length + s.length >= b->capacity)
    {
        b->capacity *= 2;
        char *new_data = (char *)realloc(b->data, b->capacity);
        if (!new_data)
            return;
        b->data = new_data;
    }
    memcpy(b->data + b->length, s.data, s.length);
    b->length += s.length;
}

void buffer_writeln(Buffer b, String s)
{
    if (!b)
        return;
    buffer_write(b, s);
    while (b->length + 1 >= b->capacity)
    {
        b->capacity *= 2;
        char *new_data = (char *)realloc(b->data, b->capacity);
        if (!new_data)
            return;
        b->data = new_data;
    }
    b->data[b->length++] = '\n';
}

// --- Substring ---
String substring(String s, size_t start, size_t len)
{
    if (start >= s.length || !s.data)
        return (String){0, NULL};
    if (start + len > s.length)
        len = s.length - start;

    char *new_data = (char *)malloc(len + 1);
    if (!new_data)
        return (String){0, NULL};

    memcpy(new_data, s.data + start, len);
    new_data[len] = '\0';
    return (String){len, new_data};
}

// --- Free String Memory ---
void free_string(String str)
{
    if (str.data)
    {
        free(str.data);
    }
}

// --- String Concatenation ---
String concat(String a, String b)
{
    // Return empty string if either input is invalid
    if (!a.data && !b.data)
        return (String){0, NULL};

    // If one string is empty, return a copy of the other
    if (!a.data)
        return substring(b, 0, b.length);
    if (!b.data)
        return substring(a, 0, a.length);

    // Allocate memory for the combined string
    size_t total_length = a.length + b.length;
    char *new_data = (char *)malloc(total_length + 1);
    if (!new_data)
        return (String){0, NULL};

    // Copy both strings
    memcpy(new_data, a.data, a.length);
    memcpy(new_data + a.length, b.data, b.length);
    new_data[total_length] = '\0';

    return (String){total_length, new_data};
}

// --- Integer to String Conversion ---
String to_string(int64_t value)
{
    // Handle special case of 0
    if (value == 0)
    {
        char *data = (char *)malloc(2);
        if (!data)
            return (String){0, NULL};
        data[0] = '0';
        data[1] = '\0';
        return (String){1, data};
    }

    // Determine sign and make value positive for processing
    int is_negative = (value < 0);
    int64_t abs_value = is_negative ? -value : value;

    // Calculate number of digits needed
    int digit_count = 0;
    int64_t temp = abs_value;
    while (temp > 0)
    {
        digit_count++;
        temp /= 10;
    }

    // Allocate memory: digits + potential minus sign + null terminator
    size_t total_length = digit_count + (is_negative ? 1 : 0);
    char *data = (char *)malloc(total_length + 1);
    if (!data)
        return (String){0, NULL};

    // Fill in digits from right to left
    data[total_length] = '\0';
    int pos = total_length - 1;
    while (abs_value > 0)
    {
        data[pos--] = '0' + (abs_value % 10);
        abs_value /= 10;
    }

    // Add minus sign if negative
    if (is_negative)
        data[0] = '-';

    return (String){total_length, data};
}

// --- String to Integer Conversion (strict) ---
int64_t str_to_int(String s)
{
    if (!s.data || s.length == 0)
        return 0;

    size_t i = 0;
    int sign = 1;
    if (s.data[0] == '-' || s.data[0] == '+')
    {
        sign = (s.data[0] == '-') ? -1 : 1;
        i = 1;
    }

    if (i >= s.length)
        return 0;

    int64_t value = 0;
    for (; i < s.length; i++)
    {
        char c = s.data[i];
        if (c < '0' || c > '9')
            return 0;
        value = (value * 10) + (c - '0');
    }

    return value * sign;
}

// --- File Handling ---

// Helper: convert MML String to null-terminated C string
static char *to_cstr(String s)
{
    char *cstr = (char *)malloc(s.length + 1);
    if (!cstr)
        return NULL;
    memcpy(cstr, s.data, s.length);
    cstr[s.length] = '\0';
    return cstr;
}

int open_file(const char *path, const char *mode)
{
    int flags = (!strcmp(mode, "r")) ? O_RDONLY : O_WRONLY | O_CREAT | O_TRUNC;
    return open(path, flags, 0644);
}

int open_file_read(String path)
{
    char *cpath = to_cstr(path);
    if (!cpath)
        return -1;
    int fd = open(cpath, O_RDONLY, 0);
    free(cpath);
    return fd;
}

int open_file_write(String path)
{
    char *cpath = to_cstr(path);
    if (!cpath)
        return -1;
    int fd = open(cpath, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    free(cpath);
    return fd;
}

int open_file_append(String path)
{
    char *cpath = to_cstr(path);
    if (!cpath)
        return -1;
    int fd = open(cpath, O_WRONLY | O_CREAT | O_APPEND, 0644);
    free(cpath);
    return fd;
}

ssize_t read_file(int fd, char *buffer, size_t size)
{
    return read(fd, buffer, size);
}

ssize_t write_file(int fd, const char *buffer, size_t size)
{
    return write(fd, buffer, size);
}

void close_file(int fd)
{
    close(fd);
}

// --- Process Execution ---
int run_process(const char *cmd, char *const argv[])
{
    pid_t pid = fork();
    if (pid == 0)
    {
        execvp(cmd, argv);
        exit(1);
    }
    int status;
    waitpid(pid, &status, 0);
    return WEXITSTATUS(status);
}

int run_process_with_output(const char *cmd, char *const argv[], char *output, size_t size)
{
    int pipefd[2];
    if (pipe(pipefd) == -1)
        return -1;

    pid_t pid = fork();
    if (pid == 0)
    {
        close(pipefd[0]);
        dup2(pipefd[1], STDOUT_FILENO);
        close(pipefd[1]);
        execvp(cmd, argv);
        exit(1);
    }
    close(pipefd[1]);
    ssize_t bytes = read(pipefd[0], output, size - 1);
    close(pipefd[0]);
    if (bytes > 0)
        output[bytes] = '\0';
    int status;
    waitpid(pid, &status, 0);
    return WEXITSTATUS(status);
}
