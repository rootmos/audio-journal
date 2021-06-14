#include <pulse/pulseaudio.h>
#include <string.h>
#include <stdio.h>

#include <r.h>

struct state {
    char choice[128];
    pa_mainloop_api* m;
};

static void signal_callback(pa_mainloop_api* m,
                            pa_signal_event* e,
                            int sig, void* opaque)
{
    debug("signal %s (%d)", strsignal(sig), sig);
    m->quit(m, 0);
}

static void source_info_callback(pa_context* c,
                                 const pa_source_info* i, int end_of_list,
                                 void* opaque)
{
    if(end_of_list) return;

    if(i->monitor_of_sink != PA_INVALID_INDEX) {
        debug("monitor source: name=%s index=%"PRIu32, i->name, i->index);
        return;
    }

    debug("source: name=%s index=%"PRIu32, i->name, i->index);

    const char* key = NULL; void* j = NULL;
    while(1) {
        key = pa_proplist_iterate(i->proplist, &j);
        if(key == NULL) {
            break;
        }

        const void* data; size_t n;
        pa_proplist_get(i->proplist, key, &data, &n);

        debug("prop: %s=%s", key, (char*)data);
    }

    struct state* st = opaque;
    sprintf(st->choice, "pulse:%"PRIu32, i->index);
    st->m->quit(st->m, 0);
}

static void event_callback(pa_context* c,
                           pa_subscription_event_type_t t, uint32_t idx,
                           void* opaque)
{
    if(t == (PA_SUBSCRIPTION_EVENT_NEW | PA_SUBSCRIPTION_EVENT_SOURCE)) {
        debug("new source idx=%"PRIu32, idx);

        pa_context_get_source_info_by_index(c, idx, source_info_callback, opaque);
    } else {
        trace("ignored event: type=%d idx=%"PRIu32, t, idx);
    }
}

static void subscribe_success_callback(pa_context* c,
                                       int idx, void* opaque)
{
    pa_context_set_subscribe_callback(c, event_callback, opaque);
}


static void ready(pa_context* c, void* opaque)
{
    pa_context_get_source_info_list(c, source_info_callback, opaque);

    pa_context_subscribe(c, PA_SUBSCRIPTION_MASK_SOURCE,
                         subscribe_success_callback, opaque);
}

static void context_state_callback(pa_context* c,
                                   void* opaque)
{
    pa_context_state_t st = pa_context_get_state(c);
    switch(st) {
    case PA_CONTEXT_UNCONNECTED: debug("state: unconnected"); break;
    case PA_CONTEXT_CONNECTING: debug("state: connecting"); break;
    case PA_CONTEXT_AUTHORIZING: debug("state: authorizing"); break;
    case PA_CONTEXT_SETTING_NAME: debug("state: setting name"); break;
    case PA_CONTEXT_READY: debug("state: ready"); ready(c, opaque); break;
    case PA_CONTEXT_FAILED: debug("state: failed"); break;
    case PA_CONTEXT_TERMINATED: debug("state: terminated"); break;
    default:
        debug("unhandled state: %d", st);
    }
}

int main(int argc, char* argv[])
{
    pa_mainloop* l = pa_mainloop_new();
    CHECK_NOT(l, NULL, "pa_mainloop_new");

    struct state st;
    memset(&st, 0, sizeof(st));
    st.m = pa_mainloop_get_api(l);

    const char* client_name = argv[0];
    pa_context* pactx = pa_context_new(st.m, client_name);
    CHECK_NOT(pactx, NULL, "pa_context_new");

    pa_context_set_state_callback(pactx, context_state_callback, &st);

    const char* server = NULL;
    pa_context_connect(pactx, server, 0, NULL);

    pa_signal_init(st.m);
    pa_signal_new(SIGINT, signal_callback, NULL);
    pa_signal_new(SIGTERM, signal_callback, NULL);

    int ec;
    pa_mainloop_run(l, &ec);
    debug("stopping");

    pa_signal_done();
    pa_mainloop_free(l);

    if(st.choice) {
        printf("%s\n", st.choice);
    }

    return ec;
}
