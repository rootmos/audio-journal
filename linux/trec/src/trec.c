#include <poll.h>
#include <sys/signalfd.h>

#include <alsa/asoundlib.h>

#include <r.h>

struct state {
    int running;
    int sfd;

    snd_pcm_t* pcm;

    void* buf;
    size_t frame, frame_i, frame_j, frame_n;
    size_t i, j, n;
};

static void alsa_init(struct state* st)
{
    const char* device = "default";
    const size_t channels = 2;
    const unsigned int rate = 44100;
    st->frame_n = 1 /* seconds */ * rate;

    int r = snd_pcm_open(&st->pcm, device,
                         SND_PCM_STREAM_CAPTURE, SND_PCM_NONBLOCK);
    CHECK_ALSA(r, "failed to open device %s", device);

    r = snd_pcm_set_params(st->pcm,
                           SND_PCM_FORMAT_S16_LE,
                           SND_PCM_ACCESS_RW_INTERLEAVED,
                           channels,
                           rate,
                           0, /* soft resample */
                           0); /* required latency */
    CHECK_ALSA(r, "snd_pcm_set_params");

    st->frame = sizeof(uint16_t)*channels;
    st->n = st->frame * st->frame_n;
    st->buf = malloc(st->n); CHECK_MALLOC(st->buf);

    st->frame_i = st->frame_j = 0;
    st->i = st->j = 0;
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
    snd_pcm_uframes_t n = st->frame_n - st->frame_j;
    while(1) {
        snd_pcm_sframes_t s = snd_pcm_readi(st->pcm, (void*)st->buf, n);
        if(s == -EAGAIN) {
            break;
        }
        CHECK_ALSA(s, "snd_pcm_readi");
        debug("read %ld frames", s);
    }
}

static void signalfd_init(struct state* st)
{
    sigset_t m;
    sigemptyset(&m);
    sigaddset(&m, SIGINT);

    st->sfd = signalfd(-1, &m, 0);
    CHECK(st->sfd, "signalfd");

    int r = sigprocmask(SIG_BLOCK, &m, NULL);
    CHECK(r, "sigprocmask");

    set_blocking(st->sfd, 0);
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

int main(int argc, char* argv[])
{
    struct state st = {
        .running = 1,
    };

    alsa_init(&st);
    signalfd_init(&st);

    alsa_start(&st);

    while(st.running) {
        const int afd = snd_pcm_poll_descriptors_count(st.pcm);
        CHECK_ALSA(afd, "snd_pcm_poll_descriptors_count");
        const size_t nfds = 1;
        struct pollfd fds[nfds+afd];
        fds[0] = (struct pollfd) { .fd = st.sfd, .events = POLLIN };
        int r = snd_pcm_poll_descriptors(st.pcm, fds+nfds, afd);
        CHECK_ALSA(r, "snd_pcm_poll_descriptors");

        debug("polling");
        r = poll(fds, LENGTH(fds), -1); CHECK(r, "poll");

        if(fds[0].revents & POLLIN) {
            signalfd_handle_event(&st);
        }

        unsigned short revents;
        r = snd_pcm_poll_descriptors_revents(st.pcm, fds+nfds, afd, &revents);
        CHECK_ALSA(r, "snd_pcm_poll_descriptors_revents");

        if(revents & POLLIN) {
            alsa_handle_read(&st);
        }
    }

    debug("graceful shutdown");

    signalfd_deinit(&st);
    alsa_deinit(&st);

    return 0;
}
