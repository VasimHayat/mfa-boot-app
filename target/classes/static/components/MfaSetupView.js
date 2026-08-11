/*
 * First-time MFA enrolment: scan the QR (or type the secret), confirm with a code, then save the
 * recovery codes. The codes are shown exactly once — the server only ever stores their hashes.
 */
window.MfaSetupView = {
    emits: ['setup-complete', 'session-lost', 'restart'],
    data: function () {
        return {
            stage: 'loading', // loading | scan | codes | error
            setup: null,
            code: '',
            confirming: false,
            error: null,
            loadError: null,
            recoveryCodes: [],
            acknowledged: false
        };
    },
    computed: {
        recoveryRows: function () {
            return this.recoveryCodes.map(function (value, index) {
                return { index: index + 1, code: value };
            });
        },
        canConfirm: function () {
            return this.code.length === 6 && !this.confirming;
        }
    },
    mounted: function () {
        this.load();
    },
    methods: {
        load: function () {
            var self = this;
            this.stage = 'loading';
            this.loadError = null;
            window.App.Api.get('/api/mfa/setup').then(function (setup) {
                self.setup = setup;
                self.stage = 'scan';
            }).catch(function (apiError) {
                if (apiError.status === 401) {
                    self.$emit('session-lost');
                    return;
                }
                self.loadError = apiError.message;
                self.stage = 'error';
            });
        },
        confirm: function () {
            var self = this;
            if (!this.canConfirm) {
                return;
            }
            this.error = null;
            this.confirming = true;
            window.App.Api.post('/api/mfa/confirm', { code: this.code }).then(function (response) {
                self.recoveryCodes = response.recoveryCodes || [];
                self.stage = 'codes';
            }).catch(function (apiError) {
                if (apiError.status === 401 && apiError.code === 'unauthorized') {
                    self.$emit('session-lost');
                    return;
                }
                self.error = apiError.message;
                self.code = '';
            }).then(function () {
                self.confirming = false;
            });
        },
        onCodeChange: function () {
            // Auto-submit the moment the sixth digit lands, matching the challenge view.
            if (this.code && this.code.length === 6) {
                this.confirm();
            }
        },
        copySecret: function () {
            var self = this;
            var secret = this.setup ? this.setup.secretBase32 : '';
            var done = function () {
                self.$toast.add({ severity: 'success', summary: 'Secret copied', life: 2500 });
            };
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(secret).then(done, function () {
                    self.selectSecret();
                });
            } else {
                this.selectSecret();
            }
        },
        selectSecret: function () {
            var input = this.$refs.secretField;
            if (input && input.$el) {
                input.$el.select();
                this.$toast.add({
                    severity: 'info',
                    summary: 'Secret selected',
                    detail: 'Press Ctrl+C to copy.',
                    life: 3500
                });
            }
        },
        downloadCodes: function () {
            var body = [
                'MFA recovery codes',
                'Generated: ' + new Date().toISOString(),
                'Each code works once. Store them somewhere safe and offline.',
                ''
            ].concat(this.recoveryCodes).join('\r\n');

            var blob = new Blob([body], { type: 'text/plain;charset=utf-8' });
            var url = URL.createObjectURL(blob);
            var link = document.createElement('a');
            link.href = url;
            link.download = 'mfa-recovery-codes.txt';
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);
            URL.revokeObjectURL(url);
        },
        finish: function () {
            this.$emit('setup-complete');
        }
    },
    template: [
        '<div class="centred-pane">',
        '  <Card class="auth-card">',
        '    <template #title>',
        '      <div class="flex align-items-center justify-content-between gap-2">',
        '        <span>{{ stage === "codes" ? "Save your recovery codes" : "Set up two-factor authentication" }}</span>',
        '        <ThemeToggle />',
        '      </div>',
        '    </template>',
        '    <template #content>',

        '      <div v-if="stage === \'loading\'" class="state-pane">',
        '        <ProgressSpinner aria-label="Preparing your authenticator secret" style="width:2.5rem;height:2.5rem" />',
        '        <span>Preparing your authenticator secret&hellip;</span>',
        '      </div>',

        '      <div v-else-if="stage === \'error\'" class="flex flex-column gap-3">',
        '        <Message severity="error" :closable="false">{{ loadError }}</Message>',
        '        <div class="flex gap-2">',
        '          <Button label="Retry" icon="pi pi-refresh" @click="load" />',
        '          <Button label="Back to sign in" severity="secondary" text @click="$emit(\'restart\')" />',
        '        </div>',
        '      </div>',

        '      <div v-else-if="stage === \'scan\'" class="flex flex-column gap-4">',
        '        <ol class="setup-steps">',
        '          <li>Open Google Authenticator (or any TOTP app) on your phone.</li>',
        '          <li>Tap <strong>+</strong> then <strong>Scan a QR code</strong>.</li>',
        '          <li>Point the camera at the code below. If you cannot scan, choose',
        '              <strong>Enter a setup key</strong> and type the secret instead.</li>',
        '          <li>Enter the six-digit code the app shows to confirm.</li>',
        '        </ol>',

        '        <div class="flex justify-content-center">',
        '          <div class="qr-frame">',
        '            <img :src="setup.qrDataUri" width="200" height="200"',
        '                 alt="QR code containing your authenticator setup key" />',
        '          </div>',
        '        </div>',

        '        <div class="flex flex-column gap-2">',
        '          <label for="mfa-secret">Setup key</label>',
        '          <div class="p-inputgroup">',
        '            <InputText id="mfa-secret" ref="secretField" class="secret-text"',
        '                       :value="setup.secretBase32" readonly',
        '                       aria-describedby="mfa-secret-help" />',
        '            <Button icon="pi pi-copy" aria-label="Copy setup key" @click="copySecret" />',
        '          </div>',
        '          <small id="mfa-secret-help">Time-based, SHA1, 6 digits, refreshed every 30 seconds.</small>',
        '        </div>',

        '        <Divider />',

        '        <div class="flex flex-column gap-2">',
        '          <label id="confirm-otp-label" for="confirm-otp">Enter the six-digit code</label>',
        '          <InputOtp inputId="confirm-otp" v-model="code" :length="6" integerOnly',
        '                    :disabled="confirming" aria-labelledby="confirm-otp-label"',
        '                    @update:modelValue="onCodeChange" />',
        '          <div role="alert" aria-live="assertive">',
        '            <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>',
        '          </div>',
        '        </div>',

        '        <div class="flex gap-2 justify-content-between">',
        '          <Button label="Back to sign in" severity="secondary" text @click="$emit(\'restart\')" />',
        '          <Button label="Confirm" icon="pi pi-check" :disabled="!canConfirm"',
        '                  :loading="confirming" @click="confirm" />',
        '        </div>',
        '      </div>',

        '      <div v-else class="flex flex-column gap-3">',
        '        <Message severity="warn" :closable="false">',
        '          These codes are shown once and cannot be retrieved later. Each one works a single time.',
        '        </Message>',
        '        <DataTable :value="recoveryRows" dataKey="index" size="small"',
        '                   aria-label="Your single-use recovery codes">',
        '          <Column field="index" header="#" style="width:4rem" />',
        '          <Column field="code" header="Recovery code" bodyClass="secret-text" />',
        '        </DataTable>',
        '        <div class="flex gap-2 flex-wrap">',
        '          <Button label="Download .txt" icon="pi pi-download" severity="secondary"',
        '                  @click="downloadCodes" />',
        '        </div>',
        '        <div class="flex align-items-center gap-2">',
        '          <Checkbox inputId="ack-codes" v-model="acknowledged" :binary="true" />',
        '          <label for="ack-codes">I have saved these recovery codes somewhere safe.</label>',
        '        </div>',
        '        <Button label="Continue" icon="pi pi-arrow-right" iconPos="right"',
        '                :disabled="!acknowledged" @click="finish" />',
        '      </div>',

        '    </template>',
        '  </Card>',
        '</div>'
    ].join('\n')
};
