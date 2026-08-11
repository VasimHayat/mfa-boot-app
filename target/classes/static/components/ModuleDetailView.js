/*
 * A single module: header with tags and overall progress, plus an accordion of lessons that can be
 * ticked off individually. Back returns to the catalog with its filters intact, because the catalog
 * query string was carried into this route's URL.
 */
window.ModuleDetailView = {
    props: {
        me: { type: Object, default: null },
        slug: { type: String, required: true }
    },
    emits: ['back', 'logout', 'session-lost'],
    data: function () {
        return {
            detail: null,
            loading: true,
            error: null,
            busyLessonId: null,
            enrolling: false
        };
    },
    computed: {
        isEnrolled: function () {
            return !!this.detail && this.detail.enrollmentStatus !== 'NOT_ENROLLED';
        },
        primaryActionLabel: function () {
            if (!this.detail) {
                return 'Start';
            }
            if (this.detail.enrollmentStatus === 'NOT_ENROLLED') {
                return 'Enroll';
            }
            if (this.detail.enrollmentStatus === 'COMPLETED') {
                return 'Review';
            }
            return 'Resume';
        },
        nextLesson: function () {
            if (!this.detail) {
                return null;
            }
            return this.detail.lessons.filter(function (lesson) {
                return !lesson.completed;
            })[0] || null;
        },
        menuItems: function () {
            var self = this;
            return [{
                label: 'Catalog',
                icon: 'pi pi-th-large',
                command: function () {
                    self.$emit('back');
                }
            }];
        }
    },
    watch: {
        slug: function () {
            this.load();
        }
    },
    mounted: function () {
        this.load();
    },
    methods: {
        format: function () {
            return window.App.Format;
        },
        load: function () {
            var self = this;
            this.loading = true;
            this.error = null;
            window.App.Api.get('/api/modules/' + encodeURIComponent(this.slug)).then(function (detail) {
                self.detail = detail;
                self.loading = false;
            }).catch(function (apiError) {
                if (apiError.status === 401) {
                    self.$emit('session-lost');
                    return;
                }
                self.error = apiError.status === 404
                    ? 'That module is not available.'
                    : apiError.message;
                self.loading = false;
            });
        },
        primaryAction: function () {
            if (!this.isEnrolled) {
                this.enroll();
                return;
            }
            var lesson = this.nextLesson || this.detail.lessons[0];
            if (lesson) {
                this.focusLesson(lesson.id);
            }
        },
        focusLesson: function (lessonId) {
            var element = document.getElementById('lesson-check-' + lessonId);
            if (element) {
                element.scrollIntoView({ block: 'center' });
                element.focus();
            }
        },
        enroll: function () {
            var self = this;
            this.enrolling = true;
            window.App.Api.post('/api/modules/' + encodeURIComponent(this.slug) + '/enroll')
                .then(function () {
                    self.$toast.add({
                        severity: 'success',
                        summary: 'Enrolled',
                        detail: self.detail.title,
                        life: 3000
                    });
                    return self.load();
                })
                .catch(function (apiError) {
                    if (apiError.status === 401) {
                        self.$emit('session-lost');
                        return;
                    }
                    self.$toast.add({
                        severity: 'error',
                        summary: 'Could not enroll',
                        detail: apiError.message,
                        life: 6000
                    });
                })
                .then(function () {
                    self.enrolling = false;
                });
        },
        completeLesson: function (lesson) {
            var self = this;
            if (lesson.completed || this.busyLessonId) {
                return;
            }
            this.busyLessonId = lesson.id;
            var url = '/api/modules/' + encodeURIComponent(this.slug) + '/lessons/' + lesson.id + '/complete';

            window.App.Api.post(url).then(function (progress) {
                // Reflect the authoritative counts the server just recomputed.
                lesson.completed = true;
                self.detail.completedLessons = progress.completedLessons;
                self.detail.progressPercent = progress.progressPercent;
                self.detail.enrollmentStatus = progress.status;
                self.detail.completedAt = progress.completedAt;
                if (progress.status === 'COMPLETED') {
                    self.$toast.add({
                        severity: 'success',
                        summary: 'Module complete',
                        detail: self.detail.title,
                        life: 5000
                    });
                }
            }).catch(function (apiError) {
                if (apiError.status === 401) {
                    self.$emit('session-lost');
                    return;
                }
                self.$toast.add({
                    severity: 'error',
                    summary: 'Could not update progress',
                    detail: apiError.message,
                    life: 6000
                });
            }).then(function () {
                self.busyLessonId = null;
            });
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
        '    <Button label="Back to catalog" icon="pi pi-arrow-left" text class="mb-3"',
        '            @click="$emit(\'back\')" />',

        '    <div v-if="loading" class="flex flex-column gap-3">',
        '      <Skeleton width="60%" height="2rem" />',
        '      <Skeleton width="100%" height="1rem" />',
        '      <Skeleton width="90%" height="1rem" />',
        '      <Skeleton width="100%" height="12rem" />',
        '    </div>',

        '    <div v-else-if="error" class="flex flex-column gap-3">',
        '      <Message severity="error" :closable="false">{{ error }}</Message>',
        '      <div class="flex gap-2">',
        '        <Button label="Retry" icon="pi pi-refresh" @click="load" />',
        '        <Button label="Back to catalog" severity="secondary" text @click="$emit(\'back\')" />',
        '      </div>',
        '    </div>',

        '    <template v-else>',
        '      <Card class="mb-4">',
        '        <template #title>{{ detail.title }}</template>',
        '        <template #content>',
        '          <div class="flex flex-column gap-3">',
        '            <div class="meta-row">',
        '              <Tag :value="format().categoryLabel(detail.category)"',
        '                   :severity="format().categorySeverity(detail.category)" />',
        '              <Tag :value="format().difficultyLabel(detail.difficulty)"',
        '                   :severity="format().difficultySeverity(detail.difficulty)" />',
        '              <Chip :label="format().minutes(detail.estimatedMinutes)" icon="pi pi-clock" />',
        '              <Chip :label="detail.lessonCount + \' lessons\'" icon="pi pi-list" />',
        '            </div>',
        '            <p class="m-0 line-height-3">{{ detail.description }}</p>',
        '            <div v-if="isEnrolled" class="flex flex-column gap-1">',
        '              <ProgressBar :value="detail.progressPercent" :showValue="false" style="height:.6rem"',
        '                           :aria-label="detail.progressPercent + \'% complete\'" />',
        '              <span class="progress-caption">',
        '                {{ detail.completedLessons }} of {{ detail.lessonCount }} lessons',
        '                &middot; {{ detail.progressPercent }}%',
        '              </span>',
        '            </div>',
        '            <div>',
        '              <Button :label="primaryActionLabel" icon="pi pi-play" :loading="enrolling"',
        '                      @click="primaryAction" />',
        '            </div>',
        '          </div>',
        '        </template>',
        '      </Card>',

        '      <h2 class="text-xl mb-2">Lessons</h2>',
        '      <Accordion :multiple="true">',
        '        <AccordionTab v-for="lesson in detail.lessons" :key="lesson.id">',
        '          <template #header>',
        '            <div class="lesson-row">',
        '              <i :class="format().contentTypeIcon(lesson.contentType)" aria-hidden="true"></i>',
        '              <span class="lesson-row__title" :class="lesson.completed ? \'lesson-done\' : \'\'">',
        '                {{ lesson.orderIndex }}. {{ lesson.title }}',
        '              </span>',
        '              <Chip :label="format().minutes(lesson.estimatedMinutes)" icon="pi pi-clock" />',
        '              <i v-if="lesson.completed" class="pi pi-check-circle text-green-500"',
        '                 :aria-label="\'Completed: \' + lesson.title"></i>',
        '            </div>',
        '          </template>',

        '          <div class="flex flex-column gap-3">',
        '            <div class="text-color-secondary">',
        '              <span class="mr-3"><i class="pi pi-tag mr-1" aria-hidden="true"></i>{{ lesson.contentType }}</span>',
        '              <span><i class="pi pi-clock mr-1" aria-hidden="true"></i>{{ format().minutes(lesson.estimatedMinutes) }}</span>',
        '            </div>',
        '            <p class="m-0">Reference: <code>{{ lesson.contentRef }}</code></p>',
        '            <div class="flex align-items-center gap-2">',
        '              <Checkbox :inputId="\'lesson-check-\' + lesson.id" :binary="true"',
        '                        :modelValue="lesson.completed"',
        '                        :disabled="lesson.completed || busyLessonId === lesson.id"',
        '                        @update:modelValue="completeLesson(lesson)" />',
        '              <label :for="\'lesson-check-\' + lesson.id">',
        '                {{ lesson.completed ? "Completed" : "Mark this lesson complete" }}',
        '              </label>',
        '            </div>',
        '          </div>',
        '        </AccordionTab>',
        '      </Accordion>',
        '    </template>',
        '  </main>',
        '</div>'
    ].join('\n')
};
