/*
 * The MFA challenge. Accepts a six-digit TOTP (auto-submitted on the sixth digit) or, behind a
 * toggle, a single-use recovery code.
 */
window.MfaChallengeView = {
    emits: ['verified', 'session-lost', 'restart'],
    data: function () {
        return {
            code: '',
            recoveryCode: '',
            useRecoveryCode: false,
            verifying: false,
            error: null,
            locked: false
        };
    },
    computed: {
        canSubmit: function () {
            if (this.verifying || this.locked) {
                return false;
            }
            return this.useRecoveryCode ? this.recoveryCode.trim().length > 0 : this.code.length === 6;
        }
    },
    mounted: function () {
        this.focusFirstField();
    },
    methods: {
        focusFirstField: function () {
            var self = this;
            this.$nextTick(function () {
                var host = self.$refs.otp || self.$refs.recovery;
                if (!host || !host.$el) {
                    return;
                }
                var target = host.$el.matches('input') ? host.$el : host.$el.querySelector('input');
                if (target) {
                    target.focus();
                }
            });
        },
        onCodeChange: function () {
            // Auto-submit as soon as the sixth digit arrives.
            if (!this.useRecoveryCode && this.code && this.code.length === 6) {
                this.submit();
            }
        },
        toggleMode: function () {
            this.useRecoveryCode = !this.useRecoveryCode;
            this.error = null;
            this.code = '';
            this.recoveryCode = '';
            this.focusFirstField();
        },
        submit: function () {
            var self = this;
            if (!this.canSubmit) {
                return;
            }
            var value = this.useRecoveryCode ? this.recoveryCode.trim() : this.code;
            this.error = null;
            this.verifying = true;

            window.App.Api.post('/api/mfa/verify', { code: value }).then(function () {
                self.$emit('verified');
            }).catch(function (apiError) {
                if (apiError.status === 429) {
                    // The server has dropped the pre-auth session; only a fresh login helps now.
                    self.locked = true;
                    self.error = apiError.message;
                    self.$toast.add({
                        severity: 'error',
                        summary: 'Too many attempts',
                        detail: apiError.message,
                        life: 8000
                    });
                    return;
                }
                if (apiError.status === 401 && apiError.code === 'unauthorized') {
                    self.$emit('session-lost');
                    return;
                }
                self.error = apiError.message;
                self.code = '';
                self.recoveryCode = '';
                self.focusFirstField();
            }).then(function () {
                self.verifying = false;
            });
        }
    },
    template: [
        '<div class="centred-pane">',
        '  <Card class="auth-card">',
        '    <template #title>',
        '      <div class="flex align-items-center justify-content-between gap-2">',
        '        <span>Two-factor authentication</span>',
        '        <ThemeToggle />',
        '      </div>',
        '    </template>',
        '    <template #subtitle>',
        '      {{ useRecoveryCode',
        '         ? "Enter one of the recovery codes you saved during setup."',
        '         : "Enter the six-digit code from your authenticator app." }}',
        '    </template>',
        '    <template #content>',
        '      <form class="flex flex-column gap-4" novalidate @submit.prevent="submit">',

        '        <div v-if="locked" class="flex flex-column gap-3">',
        '          <Message severity="error" :closable="false">{{ error }}</Message>',
        '          <Button label="Back to sign in" icon="pi pi-sign-in" @click="$emit(\'restart\')" />',
        '        </div>',

        '        <template v-else>',
        '          <div v-if="!useRecoveryCode" class="flex flex-column gap-2">',
        '            <label id="otp-label" for="challenge-otp">Authentication code</label>',
        '            <InputOtp inputId="challenge-otp" ref="otp" v-model="code" :length="6" integerOnly',
        '                      :disabled="verifying" aria-labelledby="otp-label"',
        '                      @update:modelValue="onCodeChange" />',
        '          </div>',

        '          <div v-else class="flex flex-column gap-2">',
        '            <label for="recovery-code">Recovery code</label>',
        '            <InputText id="recovery-code" ref="recovery" v-model="recoveryCode"',
        '                       class="secret-text" autocomplete="one-time-code"',
        '                       :disabled="verifying" :invalid="!!error" />',
        '          </div>',

        '          <div role="alert" aria-live="assertive">',
        '            <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>',
        '          </div>',

        '          <Button type="submit" label="Verify" icon="pi pi-check"',
        '                  :disabled="!canSubmit" :loading="verifying" />',

        '          <div class="flex justify-content-between align-items-center flex-wrap gap-2">',
        '            <Button type="button" text size="small"',
        '                    :label="useRecoveryCode ? \'Use an authenticator code\' : \'Use a recovery code\'"',
        '                    :icon="useRecoveryCode ? \'pi pi-mobile\' : \'pi pi-key\'"',
        '                    @click="toggleMode" />',
        '            <Button type="button" text size="small" severity="secondary" label="Back to sign in"',
        '                    @click="$emit(\'restart\')" />',
        '          </div>',
        '        </template>',

        '      </form>',
        '    </template>',
        '  </Card>',
        '</div>'
    ].join('\n')
};
