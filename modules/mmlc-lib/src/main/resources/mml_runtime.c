#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

#if defined(__clang__) || defined(__GNUC__)
#define FORCE_INLINE __attribute__((always_inline))
#else
#define FORCE_INLINE
#endif

// --- String Struct ---
typedef struct String
{
    size_t length;
    char *data;
} String;

// --- Array Structs ---
typedef struct IntArray
{
    int64_t length;
    int64_t *data;
} IntArray;

typedef struct StringArray
{
    int64_t length;
    String *data;
} StringArray;

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
    b->capacity = 1024*8;
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

static Buffer stdout_buffer = NULL;

static Buffer get_stdout_buffer(void)
{
    if (!stdout_buffer)
        stdout_buffer = mkBuffer();
    return stdout_buffer;
}

void flush(Buffer b)
{
    if (b && b->data && b->length > 0)
    {
        write(b->fd, b->data, b->length);
        b->length = 0;
    }
}

void mml_sys_flush()
{
    Buffer out = get_stdout_buffer();
    if (out)
        flush(out);
}

FORCE_INLINE void buffer_write(Buffer b, String s)
{
    if (!b || !s.data)
        return;

    // Auto-flush if this write would overflow
    if (b->length + s.length >= b->capacity)
        flush(b);

    memcpy(b->data + b->length, s.data, s.length);
    b->length += s.length;
}

FORCE_INLINE void buffer_writeln(Buffer b, String s)
{
    if (!b)
        return;

    // Auto-flush if this write would overflow (string + newline)
    if (b->length + s.length + 1 >= b->capacity)
        flush(b);

    if (s.data)
    {
        memcpy(b->data + b->length, s.data, s.length);
        b->length += s.length;
    }
    b->data[b->length++] = '\n';
}

FORCE_INLINE static size_t format_int64(char *buffer, size_t size, int64_t value)
{
    if (!buffer || size == 0)
        return 0;

    char digits[24];
    size_t d = 0;
    size_t pos = 0;
    int64_t abs_value = value;

    if (value < 0)
    {
        if (pos + 1 >= size)
            return 0;
        buffer[pos++] = '-';
        abs_value = -value;
    }

    if (abs_value == 0)
    {
        if (pos + 1 >= size)
            return 0;
        buffer[pos++] = '0';
        return pos;
    }

    while (abs_value > 0 && d < sizeof(digits))
    {
        int64_t q = abs_value / 10;
        int64_t r = abs_value - q * 10;
        digits[d++] = (char)('0' + r);
        abs_value = q;
    }

    if (pos + d >= size)
        return 0;

    while (d > 0)
    {
        buffer[pos++] = digits[--d];
    }

    return pos;
}

FORCE_INLINE void buffer_write_int(Buffer b, int64_t value)
{
    if (!b)
        return;

    char buf[32];
    size_t len = format_int64(buf, sizeof(buf), value);
    if (len == 0)
        return;

    if (b->length + len >= b->capacity)
        flush(b);

    memcpy(b->data + b->length, buf, len);
    b->length += len;
}

FORCE_INLINE void buffer_writeln_int(Buffer b, int64_t value)
{
    if (!b)
        return;

    char buf[32];
    size_t len = format_int64(buf, sizeof(buf), value);
    if (len == 0)
        return;

    if (b->length + len + 1 >= b->capacity)
        flush(b);

    memcpy(b->data + b->length, buf, len);
    b->length += len;
    b->data[b->length++] = '\n';
}

// --- Read a line from stdin ---
String readline()
{
    mml_sys_flush();
    size_t size = 1024;
    char *buffer = (char *)malloc(size);
    if (!buffer)
        return (String){0, NULL};

    if (fgets(buffer, size, stdin))
    {
        buffer[strcspn(buffer, "\n")] = 0;
        return (String){strlen(buffer), buffer};
    }

    // Check if we hit EOF
    if (feof(stdin))
    {
        clearerr(stdin); // Clear the EOF flag
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

// --- Print a string with newline ---
void println(String str)
{
    if (!str.data)
        return;

    Buffer out = get_stdout_buffer();
    if (out)
    {
        buffer_writeln(out, str);
    }
    else
    {
        struct iovec iov[2];
        iov[0].iov_base = str.data;
        iov[0].iov_len = str.length;
        iov[1].iov_base = "\n";
        iov[1].iov_len = 1;
        writev(STDOUT_FILENO, iov, 2);
    }
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

String read_line_fd(int fd)
{
    if (fd == STDIN_FILENO)
        mml_sys_flush();
    size_t size = 1024;
    size_t len = 0;
    char *buffer = (char *)malloc(size);
    if (!buffer)
        return (String){0, NULL};

    char c;
    while (read(fd, &c, 1) == 1 && c != '\n')
    {
        if (len + 1 >= size)
        {
            size *= 2;
            char *new_buf = (char *)realloc(buffer, size);
            if (!new_buf)
            {
                free(buffer);
                return (String){0, NULL};
            }
            buffer = new_buf;
        }
        buffer[len++] = c;
    }
    buffer[len] = '\0';
    return (String){len, buffer};
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

// --- IntArray Functions ---
FORCE_INLINE IntArray ar_int_new(int64_t size)
{
    if (size <= 0)
        return (IntArray){0, NULL};

    int64_t *storage = (int64_t *)malloc((size_t)size * sizeof(int64_t));
    if (!storage)
        return (IntArray){0, NULL};

    return (IntArray){size, storage};
}

FORCE_INLINE void ar_int_set(IntArray arr, int64_t idx, int64_t value)
{
    if (!arr.data || idx < 0 || idx >= arr.length)
    {
        fprintf(stderr, "IntArray index out of bounds: %lld (length: %lld)\n",
                (long long)idx, (long long)arr.length);
        fflush(stderr);
        exit(1);
    }
    arr.data[idx] = value;
}

FORCE_INLINE void unsafe_ar_int_set(IntArray arr, int64_t idx, int64_t value)
{    
    arr.data[idx] = value;
}

FORCE_INLINE int64_t ar_int_get(IntArray arr, int64_t idx)
{
    if (!arr.data || idx < 0 || idx >= arr.length)
    {
        
        fprintf(stderr, "IntArray index out of bounds: %lld (length: %lld)\n",
                (long long)idx, (long long)arr.length);
        fflush(stderr);
        exit(1);
    }
    return arr.data[idx];
}

FORCE_INLINE int64_t unsafe_ar_int_get(IntArray arr, int64_t idx)
{    
    return arr.data[idx];
}

FORCE_INLINE int64_t ar_int_len(IntArray arr)
{
    return arr.length;
}

// --- StringArray Functions ---
FORCE_INLINE StringArray ar_str_new(int64_t size)
{
    if (size <= 0)
        return (StringArray){0, NULL};

    String *storage = (String *)malloc((size_t)size * sizeof(String));
    if (!storage)
        return (StringArray){0, NULL};

    return (StringArray){size, storage};
}

FORCE_INLINE void ar_str_set(StringArray arr, int64_t idx, String value)
{
    if (!arr.data || idx < 0 || idx >= arr.length)
    {
        
        fprintf(stderr, "StringArray index out of bounds: %lld (length: %lld)\n",
                (long long)idx, (long long)arr.length);
        fflush(stderr);
        exit(1);
    }
    arr.data[idx] = value;
}

FORCE_INLINE String ar_str_get(StringArray arr, int64_t idx)
{
    if (!arr.data || idx < 0 || idx >= arr.length)
    {
        
        fprintf(stderr, "StringArray index out of bounds: %lld (length: %lld)\n",
                (long long)idx, (long long)arr.length);
        fflush(stderr);
        exit(1);
    }
    return arr.data[idx];
}

FORCE_INLINE int64_t ar_str_len(StringArray arr)
{
    return arr.length;
}

void __mml_sys_hole(int64_t start_line, int64_t start_col, int64_t end_line, int64_t end_col)
{
    mml_sys_flush();
    fprintf(
        stderr,
        "not implemented at [%lld:%lld]-[%lld:%lld]\n",
        (long long)start_line,
        (long long)start_col,
        (long long)end_line,
        (long long)end_col
    );
    fflush(stderr);
    exit(1);
}

// --- Memory Management Free Functions ---
// Note: With compile-time ownership tracking (witness booleans), these functions
// are only called when the value is actually owned. No runtime __cap check needed.

void __free_String(String s)
{
    if (s.data)
        free(s.data);
}

void __free_Buffer(Buffer b)
{
    if (b)
    {
        if (b->data)
            free(b->data);
        free(b);
    }
}

void __free_IntArray(IntArray arr)
{
    if (arr.data)
        free(arr.data);
}

void __free_StringArray(StringArray arr)
{
    if (arr.data)
    {
        for (int64_t i = 0; i < arr.length; i++)
        {
            __free_String(arr.data[i]);
        }
        free(arr.data);
    }
}

// --- Memory Management Clone Functions ---

String __clone_String(String s)
{
    if (!s.data || s.length == 0)
        return (String){0, NULL};

    char *new_data = (char *)malloc(s.length + 1);
    if (!new_data)
        return (String){0, NULL};

    memcpy(new_data, s.data, s.length);
    new_data[s.length] = '\0';
    return (String){s.length, new_data};
}

Buffer __clone_Buffer(Buffer b)
{
    if (!b)
        return NULL;

    Buffer new_b = (Buffer)malloc(sizeof(BufferImpl));
    if (!new_b)
        return NULL;

    new_b->capacity = b->capacity;
    new_b->length = b->length;
    new_b->fd = b->fd;
    new_b->data = (char *)malloc(b->capacity);
    if (!new_b->data)
    {
        free(new_b);
        return NULL;
    }
    memcpy(new_b->data, b->data, b->length);
    return new_b;
}

IntArray __clone_IntArray(IntArray arr)
{
    if (!arr.data || arr.length <= 0)
        return (IntArray){0, NULL};

    int64_t *new_data = (int64_t *)malloc((size_t)arr.length * sizeof(int64_t));
    if (!new_data)
        return (IntArray){0, NULL};

    memcpy(new_data, arr.data, (size_t)arr.length * sizeof(int64_t));
    return (IntArray){arr.length, new_data};
}

StringArray __clone_StringArray(StringArray arr)
{
    if (!arr.data || arr.length <= 0)
        return (StringArray){0, NULL};

    String *new_data = (String *)malloc((size_t)arr.length * sizeof(String));
    if (!new_data)
        return (StringArray){0, NULL};

    for (int64_t i = 0; i < arr.length; i++)
    {
        new_data[i] = __clone_String(arr.data[i]);
    }
    return (StringArray){arr.length, new_data};
}
