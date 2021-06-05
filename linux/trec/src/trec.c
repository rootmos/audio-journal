#include <poll.h>
#include <alsa/asoundlib.h>
#include <r.h>

int main(int argc, char* argv[])
{
    const char* device = "default";

    snd_pcm_t* pcm;
    int r = snd_pcm_open(&pcm, device,
                         SND_PCM_STREAM_CAPTURE, SND_PCM_NONBLOCK);
    CHECK_ALSA(r, "failed to open device %s", device);

    const size_t channels = 2;
    r = snd_pcm_set_params(pcm,
                           SND_PCM_FORMAT_S16_LE,
                           SND_PCM_ACCESS_RW_INTERLEAVED,
                           channels,
                           44100,
                           0, /* soft_resample */
                           0); /* latency */
    CHECK_ALSA(r, "snd_pcm_set_params");

    r = snd_pcm_start(pcm);
    CHECK_ALSA(r, "snd_pcm_start");

    int nfd = snd_pcm_poll_descriptors_count(pcm);
    CHECK_ALSA(nfd, "snd_pcm_poll_descriptors_count");
    struct pollfd fds[nfd];
    r = snd_pcm_poll_descriptors(pcm, fds, nfd);
    CHECK_ALSA(r, "snd_pcm_poll_descriptors");

    r = poll(fds, LENGTH(fds), -1); CHECK(r, "poll");

    unsigned short revents;
    r = snd_pcm_poll_descriptors_revents(pcm, fds, nfd, &revents);
    CHECK_ALSA(r, "snd_pcm_poll_descriptors_revents");

    if(revents & POLLIN) {
        const size_t buffer_n_frames = 1024;
        int16_t buf[channels*buffer_n_frames];
        snd_pcm_sframes_t s = snd_pcm_readi(pcm, (void*)buf, buffer_n_frames);
        CHECK_ALSA(s, "snd_pcm_readi");
        debug("read %ld frames", s);
    }

    r = snd_pcm_close(pcm);
    CHECK_ALSA(r, "close");

    return 0;
}
