#include <pulse/pulseaudio.h>
#include <regex.h>
#include <stdio.h>
#include <string.h>

#include <r.h>

struct state {
    uint32_t choice;

    pa_mainloop_api* m;

    int invert_match;
    regex_t* pattern;
    size_t patterns;

    int hang;
};

static void print_usage(int fd, const char* prog)
{
    dprintf(fd, "usage: %s [OPTION]... [PATTERN]...\n", prog);
    dprintf(fd, "\n");
    dprintf(fd, "options:\n");
    dprintf(fd, "  -v invert match\n");
    dprintf(fd, "  -i ignore case\n");
    dprintf(fd, "  -E extended regex\n");
    dprintf(fd, "  -H hang until chosen source is removed\n");
}

static void parse_opts(struct state* st, int argc, char* argv[])
{
    int regex_flags = REG_NOSUB;

    int res;
    while((res = getopt(argc, argv, "viEHh")) != -1) {
        switch(res) {
        case 'v':
            st->invert_match = 1;
            break;
        case 'i':
            regex_flags |= REG_ICASE;
            break;
        case 'E':
            regex_flags |= REG_EXTENDED;
            break;
        case 'H':
            st->hang = 1;
            break;
        case 'h':
        default:
            print_usage(res == 'h' ? 1 : 2, argv[0]);
            exit(res == 'h' ? 0 : 1);
        }
    }

    st->patterns = argc - optind;
    st->pattern = calloc(st->patterns, sizeof(*st->pattern));
    CHECK_MALLOC(st->pattern);
    for(size_t i = 0; i < st->patterns; i++) {
        char* p = argv[optind + i];
        debug("compiling pattern: %s", p);
        int r = regcomp(&st->pattern[i], p, regex_flags);
        if(r != 0) {
            char buf[1024];
            regerror(r, NULL, LIT(buf));
            dprintf(2, "unable to compile pattern: %s (%s)\n", p, buf);
            exit(1);
        }
    }
}

static void signal_callback(pa_mainloop_api* m,
                            pa_signal_event* e,
                            int sig, void* opaque)
{
    debug("signal %s (%d)", strsignal(sig), sig);
    m->quit(m, 1);
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
    struct state* st = opaque;

    if(st->choice != PA_INVALID_INDEX) {
        return;
    }

    if(st->patterns > 0) {
        const char* key = NULL; void* j = NULL;
        while(1) {
            key = pa_proplist_iterate(i->proplist, &j);
            if(key == NULL) {
                break;
            }

            const void* data; size_t n;
            pa_proplist_get(i->proplist, key, &data, &n);

            if(0 == strcmp(key, PA_PROP_DEVICE_DESCRIPTION)
               || 0 == strcmp(key, PA_PROP_DEVICE_PRODUCT_NAME)
               || 0 == strcmp(key, PA_PROP_DEVICE_VENDOR_NAME)
               ) {
                trace("checking property: %s=%s", key, (char*)data);
                for(size_t j = 0; j < st->patterns; j++) {
                    int r = regexec(&st->pattern[j], (const char*)data,
                                    0, NULL, 0);
                    trace("pattren j=%zu r=%d", j, r);
                    if(r == 0) {
                        if(st->invert_match) {
                            debug("matched property (skipping stream): %s=%s",
                                  key, (char*)data);
                            return;
                        } else {
                            debug("matched property: %s=%s", key, (char*)data);
                            goto choose;
                        }
                    }
                }

            } else {
                trace("skipping property: %s", key);
            }
        }

        if(st->invert_match) {
            goto choose;
        } else {
            return;
        }
    }

choose:
    st->choice = i->index;
    debug("chosing stream: %"PRIu32, st->choice);

    printf("pulse:%"PRIu32"\n", st->choice);
    fflush(stdout);

    if(!st->hang) {
        st->m->quit(st->m, 0);
    }
}

static void event_callback(pa_context* c,
                           pa_subscription_event_type_t t, uint32_t idx,
                           void* opaque)
{
    struct state* st = opaque;

    if(t == (PA_SUBSCRIPTION_EVENT_NEW | PA_SUBSCRIPTION_EVENT_SOURCE)) {
        debug("new source idx=%"PRIu32, idx);
        pa_context_get_source_info_by_index(c, idx, source_info_callback, opaque);
    } else if(t == (PA_SUBSCRIPTION_EVENT_REMOVE | PA_SUBSCRIPTION_EVENT_SOURCE)) {
        if(st->hang && st->choice == idx) {
            debug("chosen source has been removed: idx=%"PRIu32, idx);
            st->m->quit(st->m, 0);
        }
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
    struct state st = { 0 };
    st.choice = PA_INVALID_INDEX;
    parse_opts(&st, argc, argv);

    pa_mainloop* l = pa_mainloop_new();
    CHECK_NOT(l, NULL, "pa_mainloop_new");
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

    return ec;
}
