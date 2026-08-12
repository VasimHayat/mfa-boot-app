/*
 * Application shell: PrimeVue wiring, the API client, a small history router, and the top-level
 * view switch (login -> mfa -> modules -> detail).
 *
 * Loaded last, after every component file. Components reach shared helpers through `window.App`
 * from inside their own functions, never at file scope.
 */
(function () {
    'use strict';

    var Vue = window.Vue;

    /**
     * Resolves a PrimeVue UMD module. Components are exported directly; plugins and composables
     * are exported as ES-module namespaces and have to be unwrapped.
     */
    function pv(path) {
        var mod = window.primevue;
        path.split('.').forEach(function (part) {
            mod = mod ? mod[part] : undefined;
        });
        if (!mod) {
            throw new Error('PrimeVue module not loaded: ' + path);
        }
        return mod.__esModule ? mod.default : mod;
    }

    // ---- API client ---------------------------------------------------------------------

    function ApiError(status, payload) {
        this.name = 'ApiError';
        this.status = status;
        this.code = (payload && payload.error) || 'error';
        this.message = (payload && payload.message) || defaultMessageFor(status);
        this.payload = payload;
    }

    ApiError.prototype = Object.create(Error.prototype);
    ApiError.prototype.constructor = ApiError;

    function defaultMessageFor(status) {
        if (status === 0) {
            return 'Could not reach the server. Check your connection and try again.';
        }
        if (status === 401) {
            return 'Your session has expired. Please sign in again.';
        }
        if (status === 403) {
            return 'That action was refused.';
        }
        if (status === 404) {
            return 'Not found.';
        }
        if (status === 429) {
            return 'Too many attempts. Please try again later.';
        }
        return 'Something went wrong. Please try again.';
    }

    function readCookie(name) {
        var match = document.cookie.match(new RegExp('(?:^|;\\s*)' + name + '=([^;]*)'));
        return match ? decodeURIComponent(match[1]) : null;
    }

    function request(method, url, body) {
        var headers = { Accept: 'application/json' };
        if (body !== undefined) {
            headers['Content-Type'] = 'application/json';
        }
        if (method !== 'GET') {
            // Spring writes the CSRF token to a readable XSRF-TOKEN cookie; echo it back on every
            // mutating request.
            var token = readCookie('XSRF-TOKEN');
            if (token) {
                headers['X-XSRF-TOKEN'] = token;
            }
        }
        return fetch(url, {
            method: method,
            headers: headers,
            credentials: 'same-origin',
            body: body === undefined ? undefined : JSON.stringify(body)
        }).then(function (response) {
            return response.text().then(function (text) {
                var payload = null;
                if (text) {
                    try {
                        payload = JSON.parse(text);
                    } catch (ignored) {
                        payload = null;
                    }
                }
                if (!response.ok) {
                    throw new ApiError(response.status, payload);
                }
                return payload;
            });
        }, function () {
            throw new ApiError(0, null);
        });
    }

    var Api = {
        get: function (url) {
            return request('GET', url);
        },
        post: function (url, body) {
            return Api.ensureCsrf().then(function () {
                return request('POST', url, body === undefined ? {} : body);
            });
        },
        del: function (url) {
            return Api.ensureCsrf().then(function () {
                return request('DELETE', url);
            });
        },
        /** The first POST of a session may land before any GET has set the cookie. */
        ensureCsrf: function () {
            if (readCookie('XSRF-TOKEN')) {
                return Promise.resolve();
            }
            return request('GET', '/api/auth/csrf').catch(function () {
                // A failure here surfaces on the real request; nothing useful to do yet.
            });
        }
    };

    // ---- Router -------------------------------------------------------------------------

    var route = Vue.reactive({ path: '/', query: {} });

    function queryFromSearch(search) {
        var result = {};
        new URLSearchParams(search).forEach(function (value, key) {
            if (Object.prototype.hasOwnProperty.call(result, key)) {
                result[key] = [].concat(result[key], value);
            } else {
                result[key] = value;
            }
        });
        return result;
    }

    function syncRouteFromLocation() {
        route.path = window.location.pathname || '/';
        route.query = queryFromSearch(window.location.search);
    }

    function buildUrl(path, query) {
        var params = new URLSearchParams();
        Object.keys(query || {}).forEach(function (key) {
            var value = query[key];
            if (value === null || value === undefined || value === '') {
                return;
            }
            if (Array.isArray(value)) {
                value.forEach(function (item) {
                    if (item !== null && item !== undefined && item !== '') {
                        params.append(key, item);
                    }
                });
            } else {
                params.append(key, value);
            }
        });
        var qs = params.toString();
        return path + (qs ? '?' + qs : '');
    }

    var Router = {
        current: route,
        push: function (path, query) {
            window.history.pushState({}, '', buildUrl(path, query));
            syncRouteFromLocation();
        },
        replace: function (path, query) {
            window.history.replaceState({}, '', buildUrl(path, query));
            syncRouteFromLocation();
        },
        back: function () {
            window.history.back();
        }
    };

    window.addEventListener('popstate', syncRouteFromLocation);
    syncRouteFromLocation();

    // ---- Presentation helpers ------------------------------------------------------------

    var CATEGORY_LABELS = {
        SECURITY: 'Security',
        ENGINEERING: 'Engineering',
        COMPLIANCE: 'Compliance',
        ONBOARDING: 'Onboarding',
        PRODUCT: 'Product'
    };

    var CATEGORY_ICONS = {
        SECURITY: 'pi pi-shield',
        ENGINEERING: 'pi pi-cog',
        COMPLIANCE: 'pi pi-verified',
        ONBOARDING: 'pi pi-compass',
        PRODUCT: 'pi pi-box'
    };

    /** Distinct severities so category and difficulty tags never read as the same badge. */
    var CATEGORY_SEVERITY = {
        SECURITY: 'info',
        ENGINEERING: 'contrast',
        COMPLIANCE: 'success',
        ONBOARDING: 'warning',
        PRODUCT: 'secondary'
    };

    var DIFFICULTY_LABELS = {
        BEGINNER: 'Beginner',
        INTERMEDIATE: 'Intermediate',
        ADVANCED: 'Advanced'
    };

    var DIFFICULTY_SEVERITY = {
        BEGINNER: 'success',
        INTERMEDIATE: 'warning',
        ADVANCED: 'danger'
    };

    var CONTENT_TYPE_ICONS = {
        VIDEO: 'pi pi-play-circle',
        ARTICLE: 'pi pi-file',
        QUIZ: 'pi pi-question-circle'
    };

    var Format = {
        categoryLabel: function (value) {
            return CATEGORY_LABELS[value] || value;
        },
        categoryIcon: function (value) {
            return CATEGORY_ICONS[value] || 'pi pi-book';
        },
        categorySeverity: function (value) {
            return CATEGORY_SEVERITY[value] || 'info';
        },
        difficultyLabel: function (value) {
            return DIFFICULTY_LABELS[value] || value;
        },
        difficultySeverity: function (value) {
            return DIFFICULTY_SEVERITY[value] || 'info';
        },
        contentTypeIcon: function (value) {
            return CONTENT_TYPE_ICONS[value] || 'pi pi-file';
        },
        minutes: function (value) {
            if (!value) {
                return '0 min';
            }
            if (value < 60) {
                return value + ' min';
            }
            var hours = Math.floor(value / 60);
            var rest = value % 60;
            return rest ? hours + ' h ' + rest + ' min' : hours + ' h';
        },
        /** Start / Continue / Review, driven purely by enrollment status. */
        actionLabel: function (status) {
            if (status === 'IN_PROGRESS') {
                return 'Continue';
            }
            if (status === 'COMPLETED') {
                return 'Review';
            }
            if (status === 'NOT_STARTED') {
                return 'Continue';
            }
            return 'Start';
        },
        lessonProgress: function (card) {
            return card.completedLessons + ' of ' + card.lessonCount + ' lessons';
        }
    };

    // ---- Theme --------------------------------------------------------------------------

    var Theme = {
        STORAGE_KEY: 'mfa-learning.theme',
        current: function () {
            try {
                return window.localStorage.getItem(Theme.STORAGE_KEY) || 'light';
            } catch (ignored) {
                return 'light';
            }
        },
        apply: function (name) {
            var link = document.getElementById('prime-theme');
            if (link) {
                link.href = name === 'dark' ? '/vendor/theme-dark.css' : '/vendor/theme-light.css';
            }
            document.documentElement.setAttribute('data-theme', name);
            try {
                window.localStorage.setItem(Theme.STORAGE_KEY, name);
            } catch (ignored) {
                // Private browsing; the theme simply will not persist.
            }
        }
    };

    Theme.apply(Theme.current());

    window.App = {
        pv: pv,
        Api: Api,
        ApiError: ApiError,
        // Exposed because the upload view sends its own XHR to get progress events, and still has
        // to attach the CSRF token itself.
        readCookie: readCookie,
        Router: Router,
        Format: Format,
        Theme: Theme,
        categories: Object.keys(CATEGORY_LABELS).map(function (value) {
            return { value: value, label: CATEGORY_LABELS[value] };
        }),
        difficulties: Object.keys(DIFFICULTY_LABELS).map(function (value) {
            return { value: value, label: DIFFICULTY_LABELS[value] };
        })
    };

    // ---- Root component -------------------------------------------------------------------

    var RootView = {
        data: function () {
            return {
                authState: 'unknown', // unknown | anonymous | mfa-setup | mfa-challenge | authenticated
                me: null,
                route: Router.current
            };
        },
        computed: {
            /** The catalog and detail views are the only ones driven by the URL. */
            view: function () {
                if (this.authState === 'unknown') {
                    return 'booting';
                }
                if (this.authState === 'anonymous') {
                    return 'login';
                }
                if (this.authState === 'mfa-setup') {
                    return 'mfa-setup';
                }
                if (this.authState === 'mfa-challenge') {
                    return 'mfa-challenge';
                }
                if (/^\/files\/?$/.test(this.route.path)) {
                    return 'documents';
                }
                return this.moduleSlug ? 'module-detail' : 'modules';
            },
            moduleSlug: function () {
                var match = /^\/modules\/([^/]+)\/?$/.exec(this.route.path);
                return match ? decodeURIComponent(match[1]) : null;
            }
        },
        mounted: function () {
            this.boot();
        },
        methods: {
            boot: function () {
                var self = this;
                return Api.get('/api/me').then(function (me) {
                    self.me = me;
                    self.authState = 'authenticated';
                    if (!/^\/(modules|files)(\/|$)/.test(self.route.path)) {
                        Router.replace('/modules', {});
                    }
                }).catch(function (error) {
                    if (error.status === 401 && error.code === 'mfa_required') {
                        // A half-finished login survived a reload; resume the step we were on.
                        self.authState = self.rememberedMfaStep() === 'setup' ? 'mfa-setup' : 'mfa-challenge';
                    } else {
                        self.authState = 'anonymous';
                        self.rememberMfaStep(null);
                    }
                });
            },
            rememberedMfaStep: function () {
                try {
                    return window.sessionStorage.getItem('mfa-learning.step');
                } catch (ignored) {
                    return null;
                }
            },
            rememberMfaStep: function (step) {
                try {
                    if (step) {
                        window.sessionStorage.setItem('mfa-learning.step', step);
                    } else {
                        window.sessionStorage.removeItem('mfa-learning.step');
                    }
                } catch (ignored) {
                    // Nothing to do; the step is a convenience, not a requirement.
                }
            },
            onLoginSuccess: function (status) {
                if (status === 'MFA_SETUP_REQUIRED') {
                    this.rememberMfaStep('setup');
                    this.authState = 'mfa-setup';
                } else {
                    this.rememberMfaStep('challenge');
                    this.authState = 'mfa-challenge';
                }
            },
            onSetupComplete: function () {
                this.rememberMfaStep('challenge');
                this.authState = 'mfa-challenge';
                this.$toast.add({
                    severity: 'success',
                    summary: 'Authenticator added',
                    detail: 'Enter a code from your app to finish signing in.',
                    life: 6000
                });
            },
            onMfaVerified: function () {
                var self = this;
                this.rememberMfaStep(null);
                Api.get('/api/me').then(function (me) {
                    self.me = me;
                    self.authState = 'authenticated';
                    Router.replace('/modules', {});
                });
            },
            /** Sent by any child that discovers the session is gone. */
            onSessionLost: function () {
                this.me = null;
                this.authState = 'anonymous';
                this.rememberMfaStep(null);
                Router.replace('/login', {});
            },
            logout: function () {
                var self = this;
                Api.post('/api/auth/logout').catch(function () {
                    // Even if the call fails, drop the client-side session.
                }).then(function () {
                    self.onSessionLost();
                    self.$toast.add({ severity: 'success', summary: 'Signed out', life: 3000 });
                });
            },
            openModule: function (slug) {
                // Carry the current catalog query so Back restores the same filtered view.
                Router.push('/modules/' + encodeURIComponent(slug), this.route.query);
            },
            openFiles: function () {
                Router.push('/files', {});
            },
            goCatalog: function () {
                Router.push('/modules', {});
            },
            backToCatalog: function () {
                Router.back();
            },
            restartLogin: function () {
                var self = this;
                Api.post('/api/auth/logout').catch(function () {
                }).then(function () {
                    self.onSessionLost();
                });
            }
        },
        template: [
            '<Toast position="top-right" />',
            '<div v-if="view === \'booting\'" class="centred-pane">',
            '  <ProgressSpinner aria-label="Loading" style="width:3rem;height:3rem" />',
            '</div>',
            '<LoginView v-else-if="view === \'login\'" @login-success="onLoginSuccess" />',
            '<MfaSetupView v-else-if="view === \'mfa-setup\'"',
            '   @setup-complete="onSetupComplete" @session-lost="onSessionLost" @restart="restartLogin" />',
            '<MfaChallengeView v-else-if="view === \'mfa-challenge\'"',
            '   @verified="onMfaVerified" @session-lost="onSessionLost" @restart="restartLogin" />',
            '<DocumentsView v-else-if="view === \'documents\'" :me="me"',
            '   @go-catalog="goCatalog" @logout="logout" @session-lost="onSessionLost" />',
            '<ModulesView v-else-if="view === \'modules\'" :me="me" :query="route.query"',
            '   @open-module="openModule" @open-files="openFiles" @logout="logout"',
            '   @session-lost="onSessionLost" />',
            '<ModuleDetailView v-else :me="me" :slug="moduleSlug"',
            '   @back="backToCatalog" @open-files="openFiles" @logout="logout"',
            '   @session-lost="onSessionLost" />'
        ].join('\n')
    };

    // ---- Bootstrap --------------------------------------------------------------------------

    var app = Vue.createApp(RootView);

    app.use(pv('config'), { ripple: true });
    app.use(pv('toastservice'));

    app.directive('ripple', pv('ripple'));
    app.directive('tooltip', pv('tooltip'));

    [
        ['Accordion', 'accordion'],
        ['AccordionTab', 'accordiontab'],
        ['Avatar', 'avatar'],
        ['Badge', 'badge'],
        ['Button', 'button'],
        ['Card', 'card'],
        ['Checkbox', 'checkbox'],
        ['Chip', 'chip'],
        ['Column', 'column'],
        ['DataTable', 'datatable'],
        ['DataView', 'dataview'],
        ['DataViewLayoutOptions', 'dataviewlayoutoptions'],
        ['Divider', 'divider'],
        ['Dropdown', 'dropdown'],
        ['InlineMessage', 'inlinemessage'],
        ['InputOtp', 'inputotp'],
        ['InputText', 'inputtext'],
        ['Menubar', 'menubar'],
        ['Message', 'message'],
        ['MultiSelect', 'multiselect'],
        ['Password', 'password'],
        ['ProgressBar', 'progressbar'],
        ['ProgressSpinner', 'progressspinner'],
        ['SelectButton', 'selectbutton'],
        ['Skeleton', 'skeleton'],
        ['Tag', 'tag'],
        ['Toast', 'toast']
    ].forEach(function (entry) {
        app.component(entry[0], pv(entry[1]));
    });

    app.component('ThemeToggle', window.ThemeToggle);
    app.component('LoginView', window.LoginView);
    app.component('MfaSetupView', window.MfaSetupView);
    app.component('MfaChallengeView', window.MfaChallengeView);
    app.component('ModulesView', window.ModulesView);
    app.component('ModuleDetailView', window.ModuleDetailView);
    app.component('DocumentsView', window.DocumentsView);

    app.mount('#app');
}());
