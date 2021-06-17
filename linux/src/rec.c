#include <poll.h>
#include <sys/signalfd.h>
#include <sys/wait.h>
#include <sys/timerfd.h>
#include <sys/socket.h>
#include <time.h>
#include <math.h>

#include <alsa/asoundlib.h>

#include <r.h>

struct options {
    enum {
        UNKNOWN_CODEC = 0,
        FLAC = 1,
        MP3 = 2,
    } codec;

    const char* lame;
    float vbr;

    const char* flac;

    const char* fn_template;
    const char* device;
    unsigned int channels;
    unsigned int rate;

    float buffer_seconds;
    float lead_in_seconds;
    float lead_out_seconds;
    float graceperiod_seconds;
    float threshold_percent;

    unsigned int monitor_period_ms;
    float peak_seconds;

    int monitor_fd;
};

static void print_usage(int fd, const char* prog)
{
    dprintf(fd, "usage: %s [OPTION]... FILENAME_TEMPLATE\n", prog);
    dprintf(fd, "\n");
    dprintf(fd, "options:\n");
    dprintf(fd, "  -c CHANNELS set number of channels to record\n");
    dprintf(fd, "  -C CODEC    encode using CODEC (MP3 or FLAC)\n");
    dprintf(fd, "  -V QUALITY  set desired VBR quality when using MP3 codec\n");
    dprintf(fd, "  -m MS       send and log audio measurements every MS milliseconds\n");
    dprintf(fd, "  -M FD       send audio measurements and metadata to FD\n");
    dprintf(fd, "  -s SEC      stop after detecting SEC seconds of silence\n");
    dprintf(fd, "  -l SEC      add SEC of sound leading in to sound threshold trigger\n");
    dprintf(fd, "  -L SEC      add SEC of sound leading out from silence threshold trigger\n");
    dprintf(fd, "  -B SEC      buffer SEC seconds of sound\n");
    dprintf(fd, "  -t PERCENT  consider a sample value below PERCENT percent of maximum as noise/silence\n");
    dprintf(fd, "  -r HZ       sampling frequency\n");
}

static void parse_opts(struct options* opts, int argc, char* argv[])
{
    opts->codec = UNKNOWN_CODEC;

    opts->lame = getenv("LAME");
    if(opts->lame == NULL) {
        opts->lame = "lame";
    }
    opts->vbr = 4.0;

    opts->flac = getenv("FLAC");
    if(opts->flac == NULL) {
        opts->flac = "flac";
    }

    opts->device = "default";
    opts->channels = 2;
    opts->rate = 44100;

    opts->buffer_seconds = 15.0;
    opts->lead_in_seconds = 0.2;
    opts->lead_out_seconds = 0.2;
    opts->graceperiod_seconds = 10.0;
    opts->threshold_percent = 1.0;

    opts->monitor_period_ms = 100;
    opts->peak_seconds = 3.0;

    opts->monitor_fd = -1;

    int res;
    while((res = getopt(argc, argv, "c:C:V:d:B:l:L:m:M:s:t:r:h")) != -1) {
        switch(res) {
        case 'c':
            res = sscanf(optarg, "%u", &opts->channels);
            if(res != 1) {
                dprintf(2, "unable to parse number of channels: %s\n", optarg);
                exit(1);
            }
            break;
        case 'C':
            if(strcmp(optarg, "MP3") == 0) {
                opts->codec = MP3;
            } else if(strcmp(optarg, "FLAC") == 0) {
                opts->codec = FLAC;
            } else {
                dprintf(2, "unsupported codec: %s\n", optarg);
                exit(1);
            }
            break;
        case 'V':
            res = sscanf(optarg, "%f", &opts->vbr);
            if(res != 1 || opts->vbr < 0.0 || opts->vbr > 10.0) {
                dprintf(2, "unable to parse as VBR quality: %s\n", optarg);
                exit(1);
            }
            break;
        case 'd':
            opts->device = strdup(optarg);
            break;
        case 'B':
            res = sscanf(optarg, "%f", &opts->buffer_seconds);
            if(res != 1 || opts->buffer_seconds < 0.0) {
                dprintf(2, "unable to parse as non-negative seconds: %s\n", optarg);
                exit(1);
            }
            break;
        case 'l':
            res = sscanf(optarg, "%f", &opts->lead_in_seconds);
            if(res != 1 || opts->lead_in_seconds < 0.0) {
                dprintf(2, "unable to parse as non-negative seconds: %s\n", optarg);
                exit(1);
            }
            break;
        case 'L':
            res = sscanf(optarg, "%f", &opts->lead_out_seconds);
            if(res != 1 || opts->lead_out_seconds < 0.0) {
                dprintf(2, "unable to parse as non-negative seconds: %s\n", optarg);
                exit(1);
            }
            break;
        case 'm':
            res = sscanf(optarg, "%d", &opts->monitor_period_ms);
            if(res != 1) {
                dprintf(2, "unable to parse monitor period as milliseconds: %s\n", optarg);
                exit(1);
            }
            break;
        case 'M':
            res = sscanf(optarg, "%d", &opts->monitor_fd);
            if(res != 1) {
                dprintf(2, "unable to parse monitor fd: %s\n", optarg);
                exit(1);
            }
            break;
        case 's':
            res = sscanf(optarg, "%f", &opts->graceperiod_seconds);
            if(res != 1 || opts->graceperiod_seconds < 0.0) {
                dprintf(2, "unable to parse as non-negative seconds: %s\n", optarg);
                exit(1);
            }
            break;
        case 't':
            res = sscanf(optarg, "%f", &opts->threshold_percent);
            if(res != 1 || opts->threshold_percent < 0.0
               || opts->threshold_percent > 100.0) {
                dprintf(2, "unable to parse as percent: %s\n", optarg);
                exit(1);
            }
            break;
        case 'r':
            res = sscanf(optarg, "%u", &opts->rate);
            if(res != 1) {
                dprintf(2, "unable to parse sampling frequency: %s\n", optarg);
                exit(1);
            }
            break;
        case 'h':
        default:
            print_usage(res == 'h' ? 1 : 2, argv[0]);
            exit(res == 'h' ? 0 : 1);
        }
    }

    if(optind != argc - 1) {
        print_usage(2, argv[0]);
        exit(1);
    }

    opts->fn_template = argv[optind];

    if(opts->codec == UNKNOWN_CODEC) {
        size_t l = strlen(opts->fn_template);
        if(l >= 4 && strcmp(".mp3", opts->fn_template + (l - 4)) == 0) {
            opts->codec = MP3;
        } else if(l >= 5 && strcmp(".flac", opts->fn_template + (l - 5)) == 0) {
            opts->codec = FLAC;
        } else {
            dprintf(2, "unable to guess codec\n");
            exit(1);
        }
    }
}


enum state_e {
    STATE_UNINITIALIZED = 0,
    STATE_WAITING,
    STATE_RECORDING,
    STATE_RECORDING_SILENCE,
    STATE_STOPPING,
};

typedef int16_t sample_t;
typedef uint16_t usample_t;
#define SAMPLE_MAX INT16_MAX

struct state {
    enum state_e state;

    int sfd;

    pid_t enc_pid;
    int enc_fd;

    snd_pcm_t* pcm;
    unsigned int channels;

    void* buf;
    size_t f, fi, fe, fj, fn, r;

    uint64_t captured_frames;

    size_t lead_in_frames;
    size_t lead_out_frames;
    size_t grace_frames;
    usample_t threshold;
    usample_t global_peak;
    size_t silent_frames;

    int mfd;
    int tfd;
    struct timespec monitor_period;
    struct rms* rms;
    struct peak* peak;
};

struct rms {
    sample_t* buf;
    size_t i, n;
    uint64_t sqs;
};

static void rms_init(struct rms* rms, size_t n)
{
    // TODO: make sure uint64_t is big enough
    rms->n = n;
    rms->i = 0;
    rms->sqs = 0;

    rms->buf = calloc(rms->n, sizeof(sample_t));
    CHECK_MALLOC(rms->buf);
}

static void rms_sample(struct rms* rms, sample_t s)
{
    sample_t t = rms->buf[rms->i];
    rms->sqs -= t*t;
    rms->buf[rms->i] = s;
    rms->sqs += s*s;

    rms->i += 1;
    if(rms->i == rms->n) {
        rms->i = 0;
    }
}

static double rms(const struct rms* rms)
{
    return sqrt((double)rms->sqs/rms->n);
}

struct peak {
    usample_t** buf;
    size_t i, n, l;
    usample_t peak;
};

static void peak_init(struct peak* peak, size_t n)
{
    peak->n = n;
    peak->i = 0;

    size_t m = 1, l = 0;
    for(; m < peak->n; m <<= 1, l++) ;
    peak->l = l;

    peak->buf = calloc(l+1, sizeof(usample_t*));
    CHECK_MALLOC(peak->buf);

    for(size_t i = 0; m > 1; m >>= 1, i++) {
        peak->buf[i] = calloc(m, sizeof(usample_t));
        CHECK_MALLOC(peak->buf[i]);
    }
    peak->buf[l] = &peak->peak;
}

static void peak_sample(struct peak* peak, sample_t ss)
{
    usample_t s = ss >= 0 ? ss : -ss;
    size_t i = peak->i;
    size_t l = 0;
    peak->buf[0][i] = s;
    while(l < peak->l) {
        size_t j = (i % 2) == 0 ? i + 1 : i - 1;
        usample_t t = peak->buf[l][j];

        usample_t u = peak->buf[l+1][i >> 1];
        usample_t v = MAX(s, t);
        if(u == v) {
            break;
        }

        i >>= 1;
        s = peak->buf[++l][i] = v;
    }

    peak->i += 1;
    if(peak->i == peak->n) {
        peak->i = 0;
    }
}

static inline usample_t peak(struct peak* peak)
{
    return peak->peak;
}

static void timespec_from_ms(struct timespec* ts, unsigned int ms)
{
    unsigned int s = ts->tv_sec = ms / 1000;
    ms -= s * 1000;
    ts->tv_nsec = ms * 1000000;
}

static void monitor_init(struct state* st, const struct options* opts)
{
    st->mfd = opts->monitor_fd;
    if(st->mfd >= 0) {
        set_blocking(st->mfd, 0);
    }

    st->lead_in_frames = opts->lead_in_seconds * opts->rate;
    if(st->lead_in_frames > st->fn) {
        failwith("buffer too small for %f seconds of lead in",
                 opts->lead_in_seconds);
    }

    st->lead_out_frames = opts->lead_out_seconds * opts->rate;
    if(st->lead_out_frames > st->fn) {
        failwith("buffer too small for %f seconds of lead out",
                 opts->lead_out_seconds);
    }

    st->grace_frames = opts->graceperiod_seconds * opts->rate;
    if(st->grace_frames > st->fn) {
        failwith("buffer too small for a %f seconds grace period",
                 opts->graceperiod_seconds);
    }

    st->threshold = opts->threshold_percent/100 * SAMPLE_MAX;
    st->global_peak = 0;

    st->tfd = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK | TFD_CLOEXEC);
    CHECK(st->tfd, "timerfd_create");

    timespec_from_ms(&st->monitor_period, opts->monitor_period_ms);

    st->rms = calloc(opts->channels, sizeof(struct rms));
    CHECK_MALLOC(st->rms);
    for(size_t i = 0; i < opts->channels; i++) {
        rms_init(&st->rms[i], (size_t)opts->monitor_period_ms * opts->rate / 1000);
    }

    st->peak = calloc(opts->channels, sizeof(struct peak));
    CHECK_MALLOC(st->peak);
    for(size_t i = 0; i < opts->channels; i++) {
        peak_init(&st->peak[i], opts->peak_seconds * opts->rate);
    }
}

static void monitor_start(struct state* st)
{
    struct itimerspec its = {
        .it_interval = st->monitor_period,
        .it_value = st->monitor_period,
    };
    int r = timerfd_settime(st->tfd, 0, &its, NULL);
    CHECK(r, "timerfd_settime");
}

static void monitor_deinit(struct state* st)
{
    int r = close(st->tfd); CHECK(r, "close");
}

static void monitor_tick(struct state* st)
{
    size_t ticks = 0;
    while(1) {
        uint64_t t = 0;
        ssize_t r = read(st->tfd, &t, sizeof(t));
        if(r == -1 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            break;
        }
        CHECK(r, "read");
        if(r != sizeof(t)) {
            failwith("unexpected partial read");
        }
        ticks += t;
    }
    if(ticks == 0) {
        return;
    }
    if(ticks > 1) {
        warning("missed monitor ticks: %zu", ticks - 1);
    }

    for(size_t i = 0; i < st->channels; i++) {
        trace("channel %zu: RMS%%=%.2f peak%%=%.2f", i,
              100.0*rms(&st->rms[i])/SAMPLE_MAX,
              100.0*peak(&st->peak[i])/SAMPLE_MAX);
    }

    if(st->mfd >= 0) {
        struct __attribute__((__packed__)) {
            unsigned char state;
            uint64_t captured_frames;
            usample_t rms[st->channels];
            usample_t peak[st->channels];
        } msg;
        msg.state = st->state;
        msg.captured_frames = st->captured_frames;

        for(size_t i = 0; i < st->channels; i++) {
            msg.rms[i] = round(rms(&st->rms[i]));
            msg.peak[i] = peak(&st->peak[i]);
        }

        ssize_t s = send(st->mfd, &msg, sizeof(msg), 0);
        if(s == -1 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            warning("dropped monitoring message");
        } else {
            CHECK(s, "send");
            if(s != sizeof(msg)) {
                failwith("unexpected partial write");
            }
        }
    }
}

static void monitor_handle_new_frames(struct state* st,
                                      const sample_t* samples,
                                      snd_pcm_uframes_t n)
{
    for(size_t i = 0; i < n; i++) {
        for(size_t j = 0; j < st->channels; j++) {
            sample_t s = samples[i*st->channels+j];

            rms_sample(&st->rms[j], s);
            peak_sample(&st->peak[j], s);

            usample_t abs = s >= 0 ? s : -s;
            if(abs > st->global_peak) {
                st->global_peak = abs;
            }
        }
    }
}

static int monitor_check_for_sound(struct state* st)
{
    if(st->global_peak > st->threshold) {
        return 1;
    }

    if(st->fi <= st->fj) {
        size_t n = st->fj - st->fi;
        if(n > st->lead_in_frames) {
            st->fi = st->fj - st->lead_in_frames;
        }
    } else {
        size_t n = st->fn - st->fi;
        if(n + st->fj > st->lead_in_frames) {
            if(st->lead_in_frames <= st->fj) {
                st->fi = st->fj - st->lead_in_frames;
            } else {
                st->fi += st->lead_in_frames - st->fj;
            }
        }
    }

    return 0;
}

static void add_lead_out_frames(struct state* st)
{
    if(st->fe <= st->fj) {
        st->fe = MIN(st->fj, st->fe + st->lead_out_frames);
    } else {
        size_t n = st->fn - st->fe;
        if(n >= st->lead_out_frames) {
            st->fe += st->lead_out_frames;
        } else {
            size_t m = st->lead_out_frames - n;
            st->fe = MIN(m, st->fj);
        }
    }
}

static int host_is_little_endian(void)
{
    int n = 1;
    return (*(char *)&n == 1) ? 1 : 0;
}

static void alsa_init(struct state* st, const struct options* opts)
{
    int r = snd_pcm_open(&st->pcm, opts->device,
                     SND_PCM_STREAM_CAPTURE, SND_PCM_NONBLOCK);
    CHECK_ALSA(r, "failed to open device %s", opts->device);

    const snd_pcm_format_t fmt = SND_PCM_FORMAT_S16_LE;

    if(!host_is_little_endian()) {
        failwith("unimplemented host endianness");
    }

    snd_pcm_hw_params_t* hw;
    snd_pcm_hw_params_alloca(&hw);
    r = snd_pcm_hw_params_any(st->pcm, hw);
    CHECK_ALSA(r, "snd_pcm_hw_params_any");

    if(snd_pcm_hw_params_test_format(st->pcm, hw, fmt) != 0) {
        failwith("format not supported");
    }

    if(snd_pcm_hw_params_test_channels(st->pcm, hw, opts->channels) != 0) {
        failwith("unsupported number of channels: %u", opts->channels);
    }
    st->channels = opts->channels;

    if(snd_pcm_hw_params_test_rate(st->pcm, hw, opts->rate, 0) != 0) {
        failwith("unsupported sampling rate: %u", opts->rate);
    }

    st->fn = opts->buffer_seconds * opts->rate;

    r = snd_pcm_set_params(st->pcm,
                           fmt,
                           SND_PCM_ACCESS_RW_INTERLEAVED,
                           st->channels,
                           opts->rate,
                           0, /* soft resample */
                           0); /* required latency */
    CHECK_ALSA(r, "snd_pcm_set_params");

    st->f = sizeof(sample_t)*st->channels;
    size_t n = st->f * st->fn;
    st->buf = malloc(n); CHECK_MALLOC(st->buf);
    st->fi = st->fe = st->fj = st->r = 0;
    st->captured_frames = 0;
}

static void alsa_deinit(struct state* st)
{
    int r = snd_pcm_close(st->pcm);
    CHECK_ALSA(r, "close");
}

static void alsa_start(struct state* st)
{
    int r = snd_pcm_start(st->pcm);
    CHECK_ALSA(r, "snd_pcm_start");
}

static void alsa_handle_read(struct state* st)
{
    while(1) {
        trace("fi=%zu fe=%zu fj=%zu fn=%zu r=%zu", st->fi, st->fe, st->fj, st->fn, st->r);
        snd_pcm_uframes_t n;
        void* buf = (char*)st->buf + st->f*st->fj;
        if(st->fi <= st->fj) {
            n = st->fn - st->fj;
        } else {
            n = st->fi - st->fj;
        }
        if(n == 0) {
            failwith("buffer overrun");
        }

        snd_pcm_sframes_t s = snd_pcm_readi(st->pcm, buf, n);
        if(s == -EAGAIN) {
            break;
        }
        CHECK_ALSA(s, "snd_pcm_readi");

        if(st->state != STATE_WAITING) {
            st->captured_frames += s;
        }
        trace("captured %ld frames (out of %zu)", s, n);

        sample_t* samples = buf;
        for(size_t i = 0; i < s; i++) {
            int silent = 1;
            for(size_t j = 0; j < st->channels; j++) {
                sample_t t = samples[i*st->channels+j];
                if((t >= 0 && t > st->threshold)
                   || (t < 0 && -t > st->threshold)) {
                    silent = st->silent_frames = 0;
                    break;
                }
            }
            if(silent) {
                st->silent_frames += 1;
            } else {
                st->fe = st->fj + i;
                if(st->fe == st->fn) {
                    st->fe = 0;
                }
            }
        }

        monitor_handle_new_frames(st, buf, s);

        st->fj += s;
        if(st->fj == st->fn) {
            st->fj = 0;
        }
    }
}

static int frames_available_to_encode(struct state* st)
{
    return st->fi != st->fe;
}

static void encoder_init(struct state* st)
{
    st->enc_pid = -1;
    st->enc_fd = -1;
}

static char* render_filename(const struct options* opts)
{
    size_t L = 1024;
    char* fn = malloc(L); CHECK_MALLOC(fn);

    time_t now = time(NULL);
    size_t n = strftime(fn, L, opts->fn_template, localtime(&now));
    if(n == 0) {
        failwith("unable to render filename template: %s", opts->fn_template);
    }

    return fn;
}

static void encoder_start(struct state* st,
                          const struct options* opts,
                          char* fn)
{
    int fds[2];
    int r = pipe(fds); CHECK(r, "pipe");

    st->enc_pid = fork(); CHECK(st->enc_pid, "fork");
    if(st->enc_pid > 0) {
        debug("encoder pid: %d", st->enc_pid);
        r = close(fds[0]); CHECK(r, "close");
        st->enc_fd = fds[1];
        set_blocking(st->enc_fd, 0);
        return;
    }

    r = close(fds[1]); CHECK(r, "close");
    r = dup2(fds[0], 0); CHECK(r, "dup2");

    if(opts->codec == MP3) {
        char* lame = strdup(opts->lame); CHECK_MALLOC(lame);

        char rate[16];
        ssize_t s = snprintf(LIT(rate), "%u.%u", opts->rate/1000, opts->rate%1000);
        CHECK_IF(s < 0, "snprintf");
        if(s == LENGTH(rate)) {
            failwith("unable to render rate");
        }

        char vbr[16];
        s = snprintf(LIT(vbr), "%f", opts->vbr);
        CHECK_IF(s < 0, "snprintf");
        if(s == LENGTH(vbr)) {
            failwith("unable to render VBR");
        }

        char* mode;
        if(opts->channels == 1) {
            mode = "m";
        } else if(opts->channels == 2) {
            mode = "s";
        } else {
            failwith("unsupported number of channels: %u", opts->channels); // or is it?
        }

        char* args[] = {
            lame,
            "--silent",
            "-V", vbr,
            "-r",
            "-m", mode,
            "-s", rate,
            "--signed", "--bitwidth", "16", "--little-endian", // format
            "-",
            fn,
            NULL,
        };

        r = execvp(lame, args); CHECK(r, "execvp: %s", lame);
    } else if(opts->codec == FLAC) {
        char* flac = strdup(opts->flac); CHECK_MALLOC(flac);

        char rate[32];
        ssize_t s = snprintf(LIT(rate), "--sample-rate=%u", opts->rate);
        CHECK_IF(s < 0, "snprintf");
        if(s == LENGTH(rate)) {
            failwith("unable to render rate");
        }

        char channels[32];
        s = snprintf(LIT(channels), "--channels=%u", opts->channels);
        CHECK_IF(s < 0, "snprintf");
        if(s == LENGTH(channels)) {
            failwith("unable to render channels");
        }

        char* args[] = {
            flac,
            "--silent",
            "--force-raw-format",
            channels,
            rate,
            "--sign=signed", "--bps=16", "--endian=little", // format
            "-o", fn,
            "-",
            NULL,
        };

        r = execvp(flac, args); CHECK(r, "execvp: %s", flac);
    } else {
        failwith("unknown codec: %d", opts->codec);
    }
}

static void encoder_write(struct state* st)
{
    while(1) {
        trace("fi=%zu fe=%zu fj=%zu fn=%zu r=%zu", st->fi, st->fe, st->fj, st->fn, st->r);
        size_t n;
        void* buf = (char*)st->buf + st->f*st->fi + st->r;
        if(st->fi <= st->fe) {
            n = (st->fe - st->fi) * st->f - st->r;
        } else {
            n = (st->fn - st->fi) * st->f - st->r;
        }
        if(n == 0) {
            break;
        }

        ssize_t s = write(st->enc_fd, buf, n);
        if(s == -1 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            break;
        }
        CHECK(s, "write");

        trace("wrote %zd bytes to encoder", s);

        size_t r = st->r + s;
        st->fi += r / st->f;
        st->r += r % st->f;
        if(st->fi == st->fn) {
            st->fi = 0;
        }
    }
}

static void encoder_deinit(struct state* st)
{
    if(st->enc_fd >= 0) {
        set_blocking(st->enc_fd, 1);
        encoder_write(st);
        int r = close(st->enc_fd); CHECK(r, "close");
    }

    if(st->enc_pid >= 0) {
        pid_t p = waitpid(st->enc_pid, NULL, 0); CHECK(p, "waitpid");
        // TODO: check state
    }
}

static void signalfd_init(struct state* st)
{
    sigset_t m;
    sigemptyset(&m);
    sigaddset(&m, SIGINT);
    sigaddset(&m, SIGTERM);

    st->sfd = signalfd(-1, &m, SFD_NONBLOCK | SFD_CLOEXEC);
    CHECK(st->sfd, "signalfd");

    int r = sigprocmask(SIG_BLOCK, &m, NULL);
    CHECK(r, "sigprocmask");
}

static void signalfd_deinit(struct state* st)
{
    int r = close(st->sfd); CHECK(r, "close");
}

static void signalfd_handle_event(struct state* st)
{
    while(1) {
        struct signalfd_siginfo si;

        ssize_t s = read(st->sfd, &si, sizeof(si));
        if(s == -1 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            break;
        }
        CHECK(s, "read");

        if(s != sizeof(si)) {
            failwith("unexpected partial read");
        }

        if(si.ssi_signo == SIGINT) {
            debug("SIGINT");
            st->state = STATE_STOPPING;
        } else if(si.ssi_signo == SIGTERM) {
            debug("SIGTERM");
            st->state = STATE_STOPPING;
        } else {
            warning("unhandled signal: %u", si.ssi_signo);
        }
    }
}

int main(int argc, char* argv[])
{
    struct options opts;
    parse_opts(&opts, argc, argv);

    struct state st = {
        .state = STATE_UNINITIALIZED,
        0
    };

    alsa_init(&st, &opts);
    signalfd_init(&st);
    monitor_init(&st, &opts);
    encoder_init(&st);

    alsa_start(&st);
    monitor_start(&st);

    st.state = STATE_WAITING;
    info("waiting");
    while(st.state != STATE_STOPPING) {
        const int afd = snd_pcm_poll_descriptors_count(st.pcm);
        CHECK_ALSA(afd, "snd_pcm_poll_descriptors_count");

        size_t nfds = 3;
        struct pollfd fds[nfds+afd];
        fds[0] = (struct pollfd) { .fd = st.sfd, .events = POLLIN };
        fds[1] = (struct pollfd) { .fd = st.enc_fd, .events = 0 };
        fds[2] = (struct pollfd) { .fd = st.tfd, .events = POLLIN };

        if((st.state == STATE_RECORDING || st.state == STATE_RECORDING_SILENCE)
           && frames_available_to_encode(&st)) {
            fds[1].events |= POLLOUT;
        }

        int r = snd_pcm_poll_descriptors(st.pcm, fds+nfds, afd);
        CHECK_ALSA(r, "snd_pcm_poll_descriptors");

        trace("polling");
        r = poll(fds, LENGTH(fds), -1); CHECK(r, "poll");

        if(fds[0].revents & POLLIN) {
            signalfd_handle_event(&st);
        }

        if(fds[1].revents & POLLOUT) {
            encoder_write(&st);
        }

        if(fds[1].revents & POLLHUP) {
            error("encoder pipe closed prematurely");
            st.state = STATE_STOPPING;
        }

        if(fds[1].revents & POLLERR) {
            error("encoder pipe errored");
            st.state = STATE_STOPPING;
        }

        if(fds[2].revents & POLLIN) {
            monitor_tick(&st);
        }

        unsigned short revents;
        r = snd_pcm_poll_descriptors_revents(st.pcm, fds+nfds, afd, &revents);
        CHECK_ALSA(r, "snd_pcm_poll_descriptors_revents");

        if(revents & POLLIN) {
            alsa_handle_read(&st);
        }

        if(st.state == STATE_WAITING) {
            if(monitor_check_for_sound(&st)) {
                char* fn = render_filename(&opts);
                info("recording: %s", fn);
                encoder_start(&st, &opts, fn);
                st.state = STATE_RECORDING;
            }
        }

        if(st.state == STATE_RECORDING) {
            if(st.silent_frames >= st.grace_frames/2) {
                st.state = STATE_RECORDING_SILENCE;
                info("silence detected: will stop in %.2f seconds",
                     opts.graceperiod_seconds/2);
            }
        }

        if(st.state == STATE_RECORDING_SILENCE) {
            if(st.silent_frames == 0) {
                st.state = STATE_RECORDING;
                info("resuming");
            } else if(st.silent_frames >= st.grace_frames) {
                info("long silence detected");
                st.state = STATE_STOPPING;
                add_lead_out_frames(&st);
            }
        }
    }

    debug("stopping");

    encoder_deinit(&st);
    monitor_deinit(&st);
    signalfd_deinit(&st);
    alsa_deinit(&st);

    return 0;
}
