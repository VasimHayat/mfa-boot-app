/* Step one of the login: username and password. */
window.LoginView = {
    emits: ['login-success'],
    data: function () {
        return {
            username: '',
            password: '',
            loading: false,
            error: null
        };
    },
    mounted: function () {
        var field = this.$refs.username;
        if (field && field.$el) {
            field.$el.focus();
        }
    },
    methods: {
        submit: function () {
            var self = this;
            this.error = null;
            if (!this.username.trim() || !this.password) {
                this.error = 'Enter both your username and your password.';
                return;
            }
            this.loading = true;
            window.App.Api.post('/api/auth/login', {
                username: this.username.trim(),
                password: this.password
            }).then(function (response) {
                self.password = '';
                self.$emit('login-success', response.status);
            }).catch(function (apiError) {
                self.error = apiError.message;
            }).then(function () {
                self.loading = false;
            });
        }
    },
    template: [
        '<div class="centred-pane">',
        '  <Card class="auth-card">',
        '    <template #title>',
        '      <div class="flex align-items-center justify-content-between gap-2">',
        '        <span>Sign in</span>',
        '        <ThemeToggle />',
        '      </div>',
        '    </template>',
        '    <template #subtitle>Enter your credentials to continue.</template>',
        '    <template #content>',
        '      <form class="flex flex-column gap-3" novalidate @submit.prevent="submit">',
        '        <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>',
        '        <div class="flex flex-column gap-2">',
        '          <label for="username">Username</label>',
        '          <InputText id="username" ref="username" v-model="username"',
        '                     autocomplete="username" :disabled="loading"',
        '                     :invalid="!!error" aria-describedby="login-error" />',
        '        </div>',
        '        <div class="flex flex-column gap-2">',
        '          <label for="password">Password</label>',
        '          <Password inputId="password" v-model="password" :feedback="false" toggleMask',
        '                    inputClass="w-full" class="w-full" :disabled="loading"',
        '                    autocomplete="current-password" :invalid="!!error"',
        '                    aria-describedby="login-error" />',
        '        </div>',
        '        <div id="login-error" class="visually-hidden" role="alert" aria-live="assertive">',
        '          {{ error }}',
        '        </div>',
        '        <Button type="submit" label="Sign in" icon="pi pi-sign-in"',
        '                :loading="loading" class="mt-2" />',
        '      </form>',
        '    </template>',
        '  </Card>',
        '</div>'
    ].join('\n')
};
