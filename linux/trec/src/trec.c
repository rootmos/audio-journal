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
    const char* lame;
    const char* fn_template;
    const char* device;
    unsigned int channels;
    unsigned int rate;

    size_t buffer_seconds;
    float fade_in_seconds;
    float fade_out_seconds;
    float graceperiod_seconds;
    float threshold_percent;

    unsigned int monitor_period_ms;
    float peak_seconds;
    float rms_seconds;

    int monitor_fd;
};

static void print_usage(int fd, const char* prog)
{
    dprintf(fd, "usage: %s [OPTION]... FILENAME_TEMPLATE\n", prog);
    dprintf(fd, "\n");
    dprintf(fd, "options:\n");
    dprintf(fd, "  -M FD  send audio measurements and metadata to FD\n");
}

void parse_opts(struct options* opts, int argc, char* argv[])
{
    opts->lame = "lame";
    opts->device = "default";
    opts->channels = 2;
    opts->rate = 44100;

    opts->buffer_seconds = 10.0;
    opts->fade_in_seconds = 0.2;
    opts->fade_out_seconds = 0.2;
    opts->graceperiod_seconds = 2;
    opts->threshold_percent = 1.0;

    opts->monitor_period_ms = 100;
    opts->peak_seconds = 3.0;
    opts->rms_seconds = 0.1;

    opts->monitor_fd = -1;

    int res;
    while((res = getopt(argc, argv, "M:h")) != -1) {
        switch(res) {
        case 'M':
            res = sscanf(optarg, "%d", &opts->monitor_fd);
            if(res != 1) {
                dprintf(2, "unable to parse monitor fd: %s\n", optarg);
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
}


enum state_e {
    STATE_UNINITIALIZED = 0,
    STATE_WAITING,
    STATE_RECORDING,
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

    size_t fade_in_frames;
    size_t fade_out_frames;
    size_t grace_frames;
    usample_t threshold;
    usample_t global_peak;
    size_t silent_frames;

    int tfd;
    struct timespec monitor_period;
    sample_t* mbuf;
    size_t mi, mn;
    usample_t peak; size_t peak_frames;
    uint64_t sqs; size_t rms_frames;
};

static void timespec_from_ms(struct timespec* ts, unsigned int ms)
{
    unsigned int s = ts->tv_sec = ms / 1000;
    ms -= s * 1000;
    ts->tv_nsec = ms * 1000000;
}

static void monitor_init(struct state* st, const struct options* opts)
{
    st->fade_in_frames = opts->fade_in_seconds * opts->rate;
    if(st->fade_in_frames > st->fn) {
        failwith("buffer too small for %f seconds of fade in",
                 opts->fade_in_seconds);
    }

    st->fade_out_frames = opts->fade_out_seconds * opts->rate;
    if(st->fade_out_frames > st->fn) {
        failwith("buffer too small for %f seconds of fade out",
                 opts->fade_out_seconds);
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

    st->peak_frames = opts->peak_seconds * opts->rate;
    if(st->peak_frames > st->fn) {
        failwith("buffer too small for %f seconds of peak measurement",
                 opts->peak_seconds);
    }

    st->rms_frames = opts->rms_seconds * opts->rate;
    if(st->rms_frames > st->fn) {
        failwith("buffer too small for %f seconds of RMS measurement",
                 opts->rms_seconds);
    }

    st->mn = MAX(st->peak_frames, st->rms_frames);
    st->mi = 0;
    st->mbuf = calloc(st->mn, st->f); CHECK_MALLOC(st->mbuf);
    st->peak = 0;
    st->sqs = 0;
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
        warning("missed monitor tick");
    }

    float rms = 100*sqrtf((float)st->sqs/(st->rms_frames*st->channels))/SAMPLE_MAX;
    float peak = 100.0*st->peak/SAMPLE_MAX;
    debug("RMS%%=%.2f peak%%=%.2f", rms, peak);
}

static void monitor_handle_new_frames(struct state* st,
                                      const sample_t* samples,
                                      size_t f0,
                                      snd_pcm_uframes_t n)
{
    for(size_t i = 0; i < n * st->channels; i++) {
        sample_t s = samples[i];

        // global peak sample
        usample_t abs = s >= 0 ? s : -s;
        if(abs > st->global_peak) {
            st->global_peak = abs;
        }
    }

    // silent frames
    for(size_t i = 0; i < n; i++) {
        for(size_t j = 0; j < st->channels; j++) {
            sample_t s = samples[i*st->channels+j];
            if((s >= 0 && s > st->threshold)
               || (s < 0 && -s > st->threshold)) {
                st->silent_frames = 0;
                st->fe = f0 + i;
                if(st->fe == st->fn) {
                    st->fe = 0;
                }
                break;
            }
        }
        st->silent_frames += 1;
    }

    // monitor buffer and measurements
    sample_t* m = st->mbuf + st->mi*st->channels;
    for(size_t i = 0; i < n; i++) {
        for(size_t j = 0; j < st->channels; j++) {
            sample_t s = samples[i*st->channels+j];

            // squares
            {
                st->sqs += (uint64_t)s*s;
                uint64_t t;
                if(st->rms_frames <= st->mi) {
                    t = st->mbuf[(st->mi-st->rms_frames)*st->channels + j];
                } else {
                    t = st->mbuf[(st->mn-(st->rms_frames-st->mi))*st->channels + j];
                }
                st->sqs -= t*t;
            }

            // running peak (TODO: divide and conquer aggregates)
            {
                usample_t abs = s >= 0 ? s : -s;
                if(abs > st->peak) {
                    st->peak = abs;
                } else {
                    size_t k;
                    if(st->peak_frames <= st->mi) {
                        k = (st->mi-st->peak_frames)*st->channels + j;
                    } else {
                        k = (st->mn-(st->peak_frames-st->mi))*st->channels + j;
                    }
                    sample_t t = st->mbuf[k];
                    abs = t >= 0 ? t : -t;
                    if(abs == st->peak && st->peak > 0) {
                        usample_t peak = 0;
                        size_t l = st->mi*st->channels + j;
                        while(1) {
                            k += 1;
                            if(k == st->mn*st->channels) {
                                k = 0;
                            }
                            if(k == l) {
                                break;
                            }
                            t = st->mbuf[k];
                            abs = t >= 0 ? t : -t;
                            if(abs > peak) {
                                peak = abs;
                            }
                        }
                        st->peak = peak;
                    }
                }
            }

            *m = s;
            m += 1;
        }
        st->mi += 1;
        if(st->mi == st->mn) {
            st->mi = 0;
            m = st->mbuf;
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
        if(n > st->fade_in_frames) {
            st->fi = st->fj - st->fade_in_frames;
        }
    } else {
        size_t n = st->fn - st->fi;
        if(n + st->fj > st->fade_in_frames) {
            if(st->fade_in_frames <= st->fj) {
                st->fi = st->fj - st->fade_in_frames;
            } else {
                st->fi += st->fade_in_frames - st->fj;
            }
        }
    }

    return 0;
}

static void add_fade_out_frames(struct state* st)
{
    if(st->fi <= st->fj) {
        st->fe = MIN(st->fj, st->fe + st->fade_out_frames);
    } else {
        size_t n = st->fn - st->fi;
        if(n <= st->fade_out_frames) {
            st->fe += st->fade_out_frames;
        } else {
            size_t m = st->fade_in_frames - n;
            st->fe = MIN(m, st->fj);
        }
    }
}

static void alsa_init(struct state* st, const struct options* opts)
{
    st->fn = opts->buffer_seconds * opts->rate;
    st->channels = opts->channels;

    int r = snd_pcm_open(&st->pcm, opts->device,
                         SND_PCM_STREAM_CAPTURE, SND_PCM_NONBLOCK);
    CHECK_ALSA(r, "failed to open device %s", opts->device);

    r = snd_pcm_set_params(st->pcm,
                           SND_PCM_FORMAT_S16_LE,
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

        trace("captured %ld frames (out of %zu)", s, n);
        monitor_handle_new_frames(st, buf, st->fj, s);

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

    char* lame = strdup(opts->lame); CHECK_MALLOC(lame);

    char rate[16];
    ssize_t s = snprintf(LIT(rate), "%u.%u", opts->rate/1000, opts->rate%1000);
    CHECK_IF(s < 0, "snprintf");
    if(s == LENGTH(rate)) {
        failwith("unable to render rate");
    }

    char* args[] = {
        lame,
        "-v", "-B", "128", // VBR 128k
        "--silent",
        "-r",
        "-m", "s", // channels
        "-s", rate,
        "--signed", "--bitwidth", "16", "--little-endian", // format
        "-",
        fn,
        NULL,
    };

    r = execvp(opts->lame, args); CHECK(r, "execvp: %s", opts->lame);
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
        } else {
            warning("unhandled signal: %u", si.ssi_signo);
        }
    }
}

int main(int argc, char* argv[])
{
    struct options opts;
    parse_opts(&opts, argc, argv);

    if(opts.monitor_fd >= 0) {
        set_blocking(opts.monitor_fd, 0);
        ssize_t s = send(opts.monitor_fd, LIT("hello unix world"), 0);
        CHECK(s, "send");
    }

    struct state st = {
        .state = STATE_UNINITIALIZED,
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

        if(st.state == STATE_RECORDING && frames_available_to_encode(&st)) {
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
            if(st.silent_frames >= st.grace_frames) {
                info("long silence detected");
                st.state = STATE_STOPPING;
                add_fade_out_frames(&st);
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
