#include <poll.h>
#include <sys/signalfd.h>
#include <sys/wait.h>

#include <alsa/asoundlib.h>

#include <r.h>

struct options {
    const char* lame;
    const char* fn;
    const char* device;
    unsigned int channels;
    unsigned int rate;
};

struct state {
    int running;
    int sfd;

    pid_t enc_pid;
    int enc_fd;

    snd_pcm_t* pcm;

    void* buf;
    size_t f, fi, fj, fn, r;
};

static void alsa_init(struct state* st, const struct options* opts)
{
    st->fn = 1 /* seconds */ * opts->rate;

    int r = snd_pcm_open(&st->pcm, opts->device,
                         SND_PCM_STREAM_CAPTURE, SND_PCM_NONBLOCK);
    CHECK_ALSA(r, "failed to open device %s", opts->device);

    r = snd_pcm_set_params(st->pcm,
                           SND_PCM_FORMAT_S16_LE,
                           SND_PCM_ACCESS_RW_INTERLEAVED,
                           opts->channels,
                           opts->rate,
                           0, /* soft resample */
                           0); /* required latency */
    CHECK_ALSA(r, "snd_pcm_set_params");

    st->f = sizeof(int16_t)*opts->channels;
    size_t n = st->f * st->fn;
    st->buf = malloc(n); CHECK_MALLOC(st->buf);
    st->fi = st->fj = st->r = 0;
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

        trace("captured %ld frames (buffer %zu)", s, n);
        st->fj += s;
        if(st->fj == st->fn) {
            st->fj = 0;
        }
    }
}

static int buffer_non_empty(struct state* st)
{
    return st->fi != st->fj;
}

static void encoder_init(struct state* st, const struct options* opts)
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
    char* fn = strdup(opts->fn); CHECK_MALLOC(fn);

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
        size_t n;
        void* buf = (char*)st->buf + st->f*st->fi + st->r;
        if(st->fi <= st->fj) {
            n = (st->fj - st->fi) * st->f - st->r;
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
    set_blocking(st->enc_fd, 1);
    encoder_write(st);

    int r = close(st->enc_fd); CHECK(r, "close");

    pid_t p = waitpid(st->enc_pid, NULL, 0); CHECK(p, "waitpid");
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
            debug("initiating graceful shutdown: SIGINT");
            st->running = 0;
        } else {
            warning("unhandled signal: %u", si.ssi_signo);
        }
    }
}

void parse_opts(struct options* opts, int argc, char* argv[])
{
    opts->lame = "lame";
    opts->device = "default";
    opts->channels = 2;
    opts->rate = 44100;

    if(argc < 2) {
        failwith("too few arguments");
    }

    opts->fn = argv[1];
    info("output: %s", opts->fn);
}

int main(int argc, char* argv[])
{
    struct options opts;
    parse_opts(&opts, argc, argv);

    struct state st = {
        .running = 1,
    };

    alsa_init(&st, &opts);
    signalfd_init(&st);
    encoder_init(&st, &opts);

    alsa_start(&st);

    while(st.running) {
        const int afd = snd_pcm_poll_descriptors_count(st.pcm);
        CHECK_ALSA(afd, "snd_pcm_poll_descriptors_count");

        size_t nfds = 2;
        struct pollfd fds[nfds+afd];
        fds[0] = (struct pollfd) { .fd = st.sfd, .events = POLLIN };
        fds[1] = (struct pollfd) { .fd = st.enc_fd, .events = 0 };

        if(buffer_non_empty(&st)) {
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
            st.running = 0;
        }

        if(fds[1].revents & POLLERR) {
            error("encoder pipe errored");
            st.running = 0;
        }

        unsigned short revents;
        r = snd_pcm_poll_descriptors_revents(st.pcm, fds+nfds, afd, &revents);
        CHECK_ALSA(r, "snd_pcm_poll_descriptors_revents");

        if(revents & POLLIN) {
            alsa_handle_read(&st);
        }
    }

    debug("graceful shutdown");

    encoder_deinit(&st);
    signalfd_deinit(&st);
    alsa_deinit(&st);

    return 0;
}
