/*
 * "My files": upload documents to SeaweedFS, then list, download and delete them.
 *
 * Uploads go through XMLHttpRequest rather than fetch, because it reports real progress events and
 * a 10 MB file over a slow link needs a progress bar that means something.
 */
window.DocumentsView = {
    props: {
        me: { type: Object, default: null }
    },
    emits: ['logout', 'session-lost', 'go-catalog'],
    data: function () {
        return {
            documents: [],
            usage: null,
            loading: true,
            error: null,

            uploading: false,
            uploadProgress: 0,
            uploadName: '',
            uploadError: null,

            deletingId: null,
            dragOver: false
        };
    },
    computed: {
        accept: function () {
            if (!this.usage) {
                return '';
            }
            return this.usage.allowedExtensions.map(function (extension) {
                return '.' + extension;
            }).join(',');
        },
        quotaPercent: function () {
            if (!this.usage || !this.usage.maxBytes) {
                return 0;
            }
            return Math.min(100, Math.round((this.usage.bytesUsed / this.usage.maxBytes) * 100));
        },
        quotaCaption: function () {
            if (!this.usage) {
                return '';
            }
            return this.formatBytes(this.usage.bytesUsed) + ' of ' + this.formatBytes(this.usage.maxBytes)
                + ' used  ·  ' + this.usage.fileCount + ' of ' + this.usage.maxFiles + ' files';
        },
        atFileLimit: function () {
            return !!this.usage && this.usage.fileCount >= this.usage.maxFiles;
        },
        menuItems: function () {
            var self = this;
            return [{
                label: 'Catalog',
                icon: 'pi pi-th-large',
                command: function () {
                    self.$emit('go-catalog');
                }
            }];
        }
    },
    mounted: function () {
        this.load();
    },
    methods: {
        load: function () {
            var self = this;
            this.loading = true;
            this.error = null;
            Promise.all([
                window.App.Api.get('/api/me/documents?size=100'),
                window.App.Api.get('/api/me/documents/usage')
            ]).then(function (results) {
                self.documents = results[0].content;
                self.usage = results[1];
                self.loading = false;
            }).catch(function (apiError) {
                if (apiError.status === 401) {
                    self.$emit('session-lost');
                    return;
                }
                self.error = apiError.message;
                self.loading = false;
            });
        },

        pickFile: function () {
            this.$refs.fileInput.click();
        },

        onFileChosen: function (event) {
            var file = event.target.files && event.target.files[0];
            // Reset the input so choosing the same file twice still fires a change event.
            event.target.value = '';
            if (file) {
                this.uploadFile(file);
            }
        },

        onDrop: function (event) {
            this.dragOver = false;
            var file = event.dataTransfer && event.dataTransfer.files && event.dataTransfer.files[0];
            if (file) {
                this.uploadFile(file);
            }
        },

        /** Client-side checks mirror the server's, purely so the user hears about it sooner. */
        preflight: function (file) {
            if (!this.usage) {
                return null;
            }
            if (file.size === 0) {
                return 'That file is empty.';
            }
            if (file.size > this.usage.maxFileSizeBytes) {
                return 'That file is larger than the ' + this.formatBytes(this.usage.maxFileSizeBytes) + ' limit.';
            }
            var dot = file.name.lastIndexOf('.');
            var extension = dot >= 0 ? file.name.slice(dot + 1).toLowerCase() : '';
            if (this.usage.allowedExtensions.indexOf(extension) === -1) {
                return 'Files of type "' + (extension || 'unknown') + '" cannot be uploaded.';
            }
            if (this.usage.bytesUsed + file.size > this.usage.maxBytes) {
                return 'That upload would exceed your storage allowance.';
            }
            return null;
        },

        uploadFile: function (file) {
            var self = this;
            this.uploadError = null;

            var problem = this.preflight(file);
            if (problem) {
                this.uploadError = problem;
                return;
            }

            this.uploading = true;
            this.uploadProgress = 0;
            this.uploadName = file.name;

            var form = new FormData();
            form.append('file', file);

            var request = new XMLHttpRequest();
            request.open('POST', '/api/me/documents', true);
            request.withCredentials = true;
            var token = window.App.readCookie('XSRF-TOKEN');
            if (token) {
                request.setRequestHeader('X-XSRF-TOKEN', token);
            }

            request.upload.onprogress = function (event) {
                if (event.lengthComputable) {
                    self.uploadProgress = Math.round((event.loaded / event.total) * 100);
                }
            };

            request.onload = function () {
                self.uploading = false;
                if (request.status >= 200 && request.status < 300) {
                    self.$toast.add({
                        severity: 'success',
                        summary: 'Uploaded',
                        detail: file.name,
                        life: 3000
                    });
                    self.load();
                    return;
                }
                if (request.status === 401) {
                    self.$emit('session-lost');
                    return;
                }
                var message = 'Upload failed.';
                try {
                    var body = JSON.parse(request.responseText);
                    if (body && body.message) {
                        message = body.message;
                    }
                } catch (ignored) {
                    // Keep the generic message.
                }
                self.uploadError = message;
            };

            request.onerror = function () {
                self.uploading = false;
                self.uploadError = 'Could not reach the server. Check your connection and try again.';
            };

            request.send(form);
        },

        download: function (document_) {
            // A plain navigation lets the browser handle the Content-Disposition attachment itself.
            window.location.href = '/api/me/documents/' + document_.id + '/download';
        },

        confirmDelete: function (document_) {
            var self = this;
            this.deletingId = document_.id;
            window.App.Api.del('/api/me/documents/' + document_.id).then(function () {
                self.$toast.add({
                    severity: 'success',
                    summary: 'Deleted',
                    detail: document_.filename,
                    life: 3000
                });
                self.load();
            }).catch(function (apiError) {
                if (apiError.status === 401) {
                    self.$emit('session-lost');
                    return;
                }
                self.$toast.add({
                    severity: 'error',
                    summary: 'Could not delete',
                    detail: apiError.message,
                    life: 6000
                });
            }).then(function () {
                self.deletingId = null;
            });
        },

        formatBytes: function (bytes) {
            if (bytes === null || bytes === undefined) {
                return '';
            }
            if (bytes < 1024) {
                return bytes + ' B';
            }
            if (bytes < 1024 * 1024) {
                return (bytes / 1024).toFixed(1).replace(/\.0$/, '') + ' KB';
            }
            return (bytes / (1024 * 1024)).toFixed(1).replace(/\.0$/, '') + ' MB';
        },

        formatDate: function (iso) {
            if (!iso) {
                return '';
            }
            var date = new Date(iso);
            return date.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })
                + ', ' + date.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
        },

        fileIcon: function (contentType) {
            if (!contentType) {
                return 'pi pi-file';
            }
            if (contentType.indexOf('image/') === 0) {
                return 'pi pi-image';
            }
            if (contentType === 'application/pdf') {
                return 'pi pi-file-pdf';
            }
            if (contentType === 'application/zip') {
                return 'pi pi-box';
            }
            if (contentType.indexOf('spreadsheet') !== -1 || contentType.indexOf('ms-excel') !== -1) {
                return 'pi pi-table';
            }
            if (contentType.indexOf('text/') === 0) {
                return 'pi pi-align-left';
            }
            return 'pi pi-file';
        },

        shortChecksum: function (value) {
            return value ? value.slice(0, 12) : '';
        }
    },
    template: [
        '<div>',
        '  <Menubar :model="menuItems">',
        '    <template #start>',
        '      <span class="font-bold text-lg mr-3"><i class="pi pi-graduation-cap mr-2"></i>MFA Learning</span>',
        '    </template>',
        '    <template #end>',
        '      <div class="flex align-items-center gap-2">',
        '        <ThemeToggle />',
        '        <span v-if="me" class="hidden sm:inline text-color-secondary">{{ me.username }}</span>',
        '        <Button label="Logout" icon="pi pi-sign-out" severity="secondary" text',
        '                @click="$emit(\'logout\')" />',
        '      </div>',
        '    </template>',
        '  </Menubar>',

        '  <main class="app-shell">',
        '    <h1 class="text-2xl mt-0 mb-1">My files</h1>',
        '    <p class="mt-0 text-color-secondary">',
        '      Documents you upload are stored in SeaweedFS and are visible only to you.',
        '    </p>',

        '    <Card class="mb-4">',
        '      <template #content>',
        '        <div class="dropzone" :class="dragOver ? \'dropzone--active\' : \'\'"',
        '             role="button" tabindex="0" aria-label="Upload a file"',
        '             @click="pickFile" @keydown.enter.prevent="pickFile" @keydown.space.prevent="pickFile"',
        '             @dragover.prevent="dragOver = true" @dragleave.prevent="dragOver = false"',
        '             @drop.prevent="onDrop">',
        '          <i class="pi pi-cloud-upload" aria-hidden="true"></i>',
        '          <p class="m-0 font-medium">Drop a file here, or click to choose one</p>',
        '          <p class="m-0 text-sm text-color-secondary" v-if="usage">',
        '            Up to {{ formatBytes(usage.maxFileSizeBytes) }} each &middot;',
        '            {{ usage.allowedExtensions.join(", ") }}',
        '          </p>',
        '          <input ref="fileInput" type="file" class="hidden-input" :accept="accept"',
        '                 aria-hidden="true" tabindex="-1" @change="onFileChosen" />',
        '        </div>',

        '        <div v-if="uploading" class="mt-3">',
        '          <div class="flex justify-content-between text-sm mb-1">',
        '            <span>Uploading {{ uploadName }}</span><span>{{ uploadProgress }}%</span>',
        '          </div>',
        '          <ProgressBar :value="uploadProgress" :showValue="false" style="height:.6rem"',
        '                       :aria-label="\'Upload \' + uploadProgress + \'% complete\'" />',
        '        </div>',

        '        <div class="mt-3" role="alert" aria-live="assertive">',
        '          <Message v-if="uploadError" severity="error" :closable="false">{{ uploadError }}</Message>',
        '        </div>',

        '        <Message v-if="atFileLimit" severity="warn" :closable="false" class="mt-2">',
        '          You have reached your file limit. Delete something to upload more.',
        '        </Message>',

        '        <div v-if="usage" class="mt-3 flex flex-column gap-1">',
        '          <ProgressBar :value="quotaPercent" :showValue="false" style="height:.5rem"',
        '                       :aria-label="quotaPercent + \'% of storage used\'" />',
        '          <span class="progress-caption">{{ quotaCaption }}</span>',
        '        </div>',
        '      </template>',
        '    </Card>',

        '    <div v-if="loading" class="flex flex-column gap-2">',
        '      <Skeleton height="3rem" v-for="n in 4" :key="n" />',
        '    </div>',

        '    <div v-else-if="error" class="flex flex-column gap-3">',
        '      <Message severity="error" :closable="false">{{ error }}</Message>',
        '      <div><Button label="Retry" icon="pi pi-refresh" @click="load" /></div>',
        '    </div>',

        '    <div v-else-if="!documents.length" class="state-pane">',
        '      <i class="pi pi-folder-open" aria-hidden="true"></i>',
        '      <p class="m-0">You have not uploaded anything yet</p>',
        '    </div>',

        '    <DataTable v-else :value="documents" dataKey="id" removableSort',
        '               aria-label="Your uploaded documents" class="w-full">',
        '      <Column field="filename" header="Name" sortable>',
        '        <template #body="slotProps">',
        '          <div class="flex align-items-center gap-2">',
        '            <i :class="fileIcon(slotProps.data.contentType)" aria-hidden="true"></i>',
        '            <span class="break-anywhere">{{ slotProps.data.filename }}</span>',
        '          </div>',
        '        </template>',
        '      </Column>',
        '      <Column field="sizeBytes" header="Size" sortable style="width:8rem">',
        '        <template #body="slotProps">{{ formatBytes(slotProps.data.sizeBytes) }}</template>',
        '      </Column>',
        '      <Column field="uploadedAt" header="Uploaded" sortable style="width:14rem">',
        '        <template #body="slotProps">{{ formatDate(slotProps.data.uploadedAt) }}</template>',
        '      </Column>',
        '      <Column header="SHA-256" style="width:11rem">',
        '        <template #body="slotProps">',
        '          <code class="text-sm" :title="slotProps.data.checksumSha256">',
        '            {{ shortChecksum(slotProps.data.checksumSha256) }}&hellip;',
        '          </code>',
        '        </template>',
        '      </Column>',
        '      <Column header="Actions" style="width:9rem">',
        '        <template #body="slotProps">',
        '          <div class="flex gap-1">',
        '            <Button icon="pi pi-download" text rounded',
        '                    v-tooltip.top="\'Download\'"',
        '                    :aria-label="\'Download \' + slotProps.data.filename"',
        '                    @click="download(slotProps.data)" />',
        '            <Button icon="pi pi-trash" text rounded severity="danger"',
        '                    v-tooltip.top="\'Delete\'"',
        '                    :aria-label="\'Delete \' + slotProps.data.filename"',
        '                    :loading="deletingId === slotProps.data.id"',
        '                    @click="confirmDelete(slotProps.data)" />',
        '          </div>',
        '        </template>',
        '      </Column>',
        '    </DataTable>',
        '  </main>',
        '</div>'
    ].join('\n')
};
