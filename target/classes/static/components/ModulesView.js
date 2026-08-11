/*
 * The post-login landing page: learning summary, filter bar and the paged module catalog.
 *
 * All filter state is mirrored into the URL query string, so a filtered view can be shared, survives
 * a reload, and is restored when the browser Back button returns here from a module detail page.
 */
window.ModulesView = {
    props: {
        me: { type: Object, default: null },
        query: { type: Object, required: true }
    },
    emits: ['open-module', 'logout', 'session-lost'],
    data: function () {
        return {
            summary: null,
            page: null,
            loading: true,
            error: null,
            summaryError: false,

            searchInput: '',
            filters: {
                q: '',
                categories: [],
                difficulty: null,
                statuses: [],
                sort: 'RECOMMENDED'
            },
            layout: 'grid',
            pageNumber: 0,

            applyingFromUrl: false,
            searchTimer: null,
            requestToken: 0
        };
    },
    computed: {
        categoryOptions: function () {
            return window.App.categories;
        },
        difficultyOptions: function () {
            return window.App.difficulties;
        },
        sortOptions: function () {
            return [
                { value: 'RECOMMENDED', label: 'Recommended' },
                { value: 'NEWEST', label: 'Newest' },
                { value: 'SHORTEST', label: 'Shortest' },
                { value: 'TITLE_ASC', label: 'Title A-Z' }
            ];
        },
        statusOptions: function () {
            return [
                { value: 'ALL', label: 'All' },
                { value: 'NOT_STARTED', label: 'Not started' },
                { value: 'IN_PROGRESS', label: 'In progress' },
                { value: 'COMPLETED', label: 'Completed' }
            ];
        },
        /**
         * The SelectButton works in single-choice terms while the API takes a set, because
         * "Not started" covers both a caller who never enrolled and one who enrolled and has done
         * nothing yet.
         */
        statusChoice: {
            get: function () {
                var statuses = this.filters.statuses;
                if (!statuses.length) {
                    return 'ALL';
                }
                if (statuses.indexOf('IN_PROGRESS') !== -1) {
                    return 'IN_PROGRESS';
                }
                if (statuses.indexOf('COMPLETED') !== -1) {
                    return 'COMPLETED';
                }
                return 'NOT_STARTED';
            },
            set: function (choice) {
                if (!choice || choice === 'ALL') {
                    this.filters.statuses = [];
                } else if (choice === 'NOT_STARTED') {
                    this.filters.statuses = ['NOT_ENROLLED', 'NOT_STARTED'];
                } else {
                    this.filters.statuses = [choice];
                }
            }
        },
        pageSize: function () {
            // Grid rows fit three cards; the list is denser, so it shows fewer per page.
            return this.layout === 'grid' ? 12 : 10;
        },
        firstRecord: function () {
            return this.pageNumber * this.pageSize;
        },
        totalRecords: function () {
            return this.page ? this.page.totalElements : 0;
        },
        modules: function () {
            return this.page ? this.page.content : [];
        },
        hasActiveFilters: function () {
            return !!this.filters.q
                || this.filters.categories.length > 0
                || !!this.filters.difficulty
                || this.filters.statuses.length > 0;
        },
        resultsAnnouncement: function () {
            if (this.loading) {
                return 'Loading modules';
            }
            if (this.error) {
                return 'Could not load modules';
            }
            var total = this.totalRecords;
            return total === 1 ? '1 module matches your filters' : total + ' modules match your filters';
        },
        skeletonCount: function () {
            return this.pageSize > 6 ? 6 : this.pageSize;
        },
        menuItems: function () {
            var self = this;
            return [{
                label: 'Catalog',
                icon: 'pi pi-th-large',
                command: function () {
                    self.clearFilters();
                }
            }];
        }
    },
    watch: {
        // Back/forward navigation and shared links both arrive as a changed query prop.
        query: {
            deep: true,
            handler: function (value) {
                this.applyQuery(value);
            }
        },
        filters: {
            deep: true,
            handler: function () {
                if (this.applyingFromUrl) {
                    return;
                }
                this.pageNumber = 0;
                this.pushQuery();
                this.loadCatalog();
            }
        }
    },
    created: function () {
        this.applyQuery(this.query);
    },
    mounted: function () {
        this.loadSummary();
        this.loadCatalog();
    },
    beforeUnmount: function () {
        if (this.searchTimer) {
            window.clearTimeout(this.searchTimer);
        }
    },
    methods: {
        format: function () {
            return window.App.Format;
        },

        // ---- URL <-> state -------------------------------------------------------------

        applyQuery: function (query) {
            var asArray = function (value) {
                if (value === undefined || value === null || value === '') {
                    return [];
                }
                return (Array.isArray(value) ? value : [value])
                    .join(',')
                    .split(',')
                    .filter(function (item) {
                        return item !== '';
                    });
            };

            this.applyingFromUrl = true;
            this.filters.q = query.q || '';
            this.searchInput = this.filters.q;
            this.filters.categories = asArray(query.category);
            this.filters.difficulty = query.difficulty || null;
            this.filters.statuses = asArray(query.status);
            this.filters.sort = query.sort || 'RECOMMENDED';
            this.layout = query.layout === 'list' ? 'list' : 'grid';
            this.pageNumber = Math.max(0, parseInt(query.page, 10) || 0);

            var self = this;
            this.$nextTick(function () {
                self.applyingFromUrl = false;
            });
        },

        currentQuery: function () {
            var query = {};
            if (this.filters.q) {
                query.q = this.filters.q;
            }
            if (this.filters.categories.length) {
                query.category = this.filters.categories.join(',');
            }
            if (this.filters.difficulty) {
                query.difficulty = this.filters.difficulty;
            }
            if (this.filters.statuses.length) {
                query.status = this.filters.statuses.join(',');
            }
            if (this.filters.sort && this.filters.sort !== 'RECOMMENDED') {
                query.sort = this.filters.sort;
            }
            if (this.layout !== 'grid') {
                query.layout = this.layout;
            }
            if (this.pageNumber > 0) {
                query.page = String(this.pageNumber);
            }
            return query;
        },

        pushQuery: function () {
            window.App.Router.replace('/modules', this.currentQuery());
        },

        // ---- Data ----------------------------------------------------------------------

        loadSummary: function () {
            var self = this;
            this.summaryError = false;
            window.App.Api.get('/api/me/learning/summary').then(function (summary) {
                self.summary = summary;
            }).catch(function (apiError) {
                if (apiError.status === 401) {
                    self.$emit('session-lost');
                    return;
                }
                self.summaryError = true;
            });
        },

        loadCatalog: function () {
            var self = this;
            var token = ++this.requestToken;
            this.loading = true;
            this.error = null;

            var params = new URLSearchParams();
            if (this.filters.q) {
                params.set('q', this.filters.q);
            }
            this.filters.categories.forEach(function (value) {
                params.append('category', value);
            });
            if (this.filters.difficulty) {
                params.set('difficulty', this.filters.difficulty);
            }
            this.filters.statuses.forEach(function (value) {
                params.append('status', value);
            });
            params.set('sort', this.filters.sort);
            params.set('page', String(this.pageNumber));
            params.set('size', String(this.pageSize));

            window.App.Api.get('/api/modules?' + params.toString()).then(function (page) {
                if (token !== self.requestToken) {
                    return; // A newer request has already been issued.
                }
                self.page = page;
                self.loading = false;
            }).catch(function (apiError) {
                if (token !== self.requestToken) {
                    return;
                }
                if (apiError.status === 401) {
                    self.$emit('session-lost');
                    return;
                }
                self.error = apiError.message;
                self.loading = false;
            });
        },

        // ---- Interaction ----------------------------------------------------------------

        onSearchInput: function () {
            var self = this;
            if (this.searchTimer) {
                window.clearTimeout(this.searchTimer);
            }
            this.searchTimer = window.setTimeout(function () {
                self.filters.q = self.searchInput.trim();
            }, 300);
        },

        onPage: function (event) {
            this.pageNumber = event.page;
            this.pushQuery();
            this.loadCatalog();
        },

        onLayoutChange: function (value) {
            if (value === this.layout) {
                return;
            }
            this.layout = value;
            // Page size differs per layout, so an old page index would point somewhere else.
            this.pageNumber = 0;
            this.pushQuery();
            this.loadCatalog();
        },

        clearFilters: function () {
            this.searchInput = '';
            this.filters.q = '';
            this.filters.categories = [];
            this.filters.difficulty = null;
            this.filters.statuses = [];
        },

        open: function (card) {
            this.$emit('open-module', card.slug);
        },

        resume: function () {
            if (this.summary && this.summary.continueLearning) {
                this.$emit('open-module', this.summary.continueLearning.slug);
            }
        },

        cardAriaLabel: function (card) {
            var parts = [
                card.title,
                window.App.Format.categoryLabel(card.category),
                window.App.Format.difficultyLabel(card.difficulty),
                window.App.Format.minutes(card.estimatedMinutes)
            ];
            if (card.enrollmentStatus !== 'NOT_ENROLLED') {
                parts.push(window.App.Format.lessonProgress(card) + ' complete');
            }
            return parts.join(', ');
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

        '    <Card v-if="summary && summary.continueLearning" class="mb-4">',
        '      <template #title>Continue learning</template>',
        '      <template #content>',
        '        <div class="flex flex-column md:flex-row gap-3 align-items-start md:align-items-center">',
        '          <div class="module-row__thumb"',
        '               :class="summary.continueLearning.thumbnailUrl ? \'\' : \'thumb-\' + summary.continueLearning.category">',
        '            <img v-if="summary.continueLearning.thumbnailUrl"',
        '                 :src="summary.continueLearning.thumbnailUrl" alt="" />',
        '            <i v-else :class="format().categoryIcon(summary.continueLearning.category)"',
        '               style="font-size:1.75rem;color:rgba(255,255,255,.9)" aria-hidden="true"></i>',
        '          </div>',
        '          <div class="flex-1 flex flex-column gap-2 w-full">',
        '            <h3 class="m-0">{{ summary.continueLearning.title }}</h3>',
        '            <ProgressBar :value="summary.continueLearning.progressPercent" :showValue="false"',
        '                         style="height:.6rem"',
        '                         :aria-label="summary.continueLearning.progressPercent + \'% complete\'" />',
        '            <span class="progress-caption">',
        '              {{ format().lessonProgress(summary.continueLearning) }}',
        '              &middot; {{ summary.continueLearning.progressPercent }}%',
        '            </span>',
        '          </div>',
        '          <Button label="Resume" icon="pi pi-play" @click="resume" />',
        '        </div>',
        '      </template>',
        '    </Card>',

        '    <div v-if="summary" class="grid mb-4">',
        '      <div class="col-12 sm:col-6 lg:col-3">',
        '        <Card><template #content>',
        '          <div class="text-color-secondary text-sm">Enrolled</div>',
        '          <div class="text-3xl font-bold">{{ summary.enrolledCount }}</div>',
        '        </template></Card>',
        '      </div>',
        '      <div class="col-12 sm:col-6 lg:col-3">',
        '        <Card><template #content>',
        '          <div class="text-color-secondary text-sm">In progress</div>',
        '          <div class="text-3xl font-bold">{{ summary.inProgressCount }}</div>',
        '        </template></Card>',
        '      </div>',
        '      <div class="col-12 sm:col-6 lg:col-3">',
        '        <Card><template #content>',
        '          <div class="text-color-secondary text-sm">Completed</div>',
        '          <div class="text-3xl font-bold">{{ summary.completedCount }}</div>',
        '        </template></Card>',
        '      </div>',
        '      <div class="col-12 sm:col-6 lg:col-3">',
        '        <Card><template #content>',
        '          <div class="text-color-secondary text-sm">Minutes learned</div>',
        '          <div class="text-3xl font-bold">{{ summary.totalMinutesCompleted }}</div>',
        '        </template></Card>',
        '      </div>',
        '    </div>',

        '    <Card class="mb-4">',
        '      <template #content>',
        '        <div class="filter-bar">',
        '          <span class="p-input-icon-left search-field">',
        '            <i class="pi pi-search" aria-hidden="true"></i>',
        '            <InputText id="catalog-search" v-model="searchInput" class="w-full"',
        '                       placeholder="Search modules" aria-label="Search modules by title or summary"',
        '                       @input="onSearchInput" />',
        '          </span>',
        '          <MultiSelect inputId="filter-category" v-model="filters.categories"',
        '                       :options="categoryOptions" optionLabel="label" optionValue="value"',
        '                       display="chip" placeholder="Category" aria-label="Filter by category"',
        '                       style="min-width:13rem" />',
        '          <Dropdown inputId="filter-difficulty" v-model="filters.difficulty"',
        '                    :options="difficultyOptions" optionLabel="label" optionValue="value"',
        '                    placeholder="Difficulty" showClear aria-label="Filter by difficulty"',
        '                    style="min-width:11rem" />',
        '          <SelectButton v-model="statusChoice" :options="statusOptions"',
        '                        optionLabel="label" optionValue="value" :allowEmpty="false"',
        '                        aria-label="Filter by your progress" />',
        '          <Button v-if="hasActiveFilters" label="Clear filters" icon="pi pi-filter-slash"',
        '                  severity="secondary" text @click="clearFilters" />',
        '        </div>',
        '      </template>',
        '    </Card>',

        '    <div class="flex align-items-center justify-content-between mb-3 gap-2 flex-wrap">',
        '      <span class="text-color-secondary" aria-hidden="true">',
        '        {{ loading ? "Loading\\u2026" : totalRecords + (totalRecords === 1 ? " module" : " modules") }}',
        '      </span>',
        '      <div class="visually-hidden" role="status" aria-live="polite">{{ resultsAnnouncement }}</div>',
        '      <div class="flex align-items-center gap-2">',
        '        <label for="catalog-sort" class="text-color-secondary text-sm">Sort</label>',
        '        <Dropdown inputId="catalog-sort" v-model="filters.sort" :options="sortOptions"',
        '                  optionLabel="label" optionValue="value" style="min-width:12rem" />',
        '        <DataViewLayoutOptions :modelValue="layout" @update:modelValue="onLayoutChange" />',
        '      </div>',
        '    </div>',

        '    <div v-if="loading" class="grid" aria-hidden="true">',
        '      <div v-for="n in skeletonCount" :key="n"',
        '           :class="layout === \'grid\' ? \'col-12 md:col-6 lg:col-4\' : \'col-12\'">',
        '        <div class="skeleton-card">',
        '          <Skeleton height="8rem" borderRadius="0" />',
        '          <div class="skeleton-card__body">',
        '            <Skeleton width="70%" height="1.25rem" />',
        '            <Skeleton width="100%" height=".8rem" />',
        '            <Skeleton width="85%" height=".8rem" />',
        '            <div class="flex gap-2 mt-2">',
        '              <Skeleton width="5rem" height="1.5rem" />',
        '              <Skeleton width="6rem" height="1.5rem" />',
        '            </div>',
        '            <Skeleton width="100%" height="2.4rem" class="mt-2" />',
        '          </div>',
        '        </div>',
        '      </div>',
        '    </div>',

        '    <div v-else-if="error" class="flex flex-column gap-3">',
        '      <Message severity="error" :closable="false">{{ error }}</Message>',
        '      <div><Button label="Retry" icon="pi pi-refresh" @click="loadCatalog" /></div>',
        '    </div>',

        '    <DataView v-else :value="modules" :layout="layout" dataKey="id"',
        '              :lazy="true" :paginator="true" :rows="pageSize" :first="firstRecord"',
        '              :totalRecords="totalRecords" :alwaysShowPaginator="false" @page="onPage">',

        '      <template #empty>',
        '        <div class="state-pane">',
        '          <i class="pi pi-inbox" aria-hidden="true"></i>',
        '          <p class="m-0">No modules match your filters</p>',
        '          <Button v-if="hasActiveFilters" label="Clear filters" icon="pi pi-filter-slash"',
        '                  @click="clearFilters" />',
        '        </div>',
        '      </template>',

        '      <template #grid="slotProps">',
        '        <div class="grid">',
        '          <div v-for="card in slotProps.items" :key="card.id" class="col-12 md:col-6 lg:col-4">',
        '            <div class="module-card" tabindex="0" role="link"',
        '                 :aria-label="cardAriaLabel(card)"',
        '                 @click="open(card)" @keydown.enter.prevent="open(card)"',
        '                 @keydown.space.prevent="open(card)">',
        '              <div class="module-card__thumb"',
        '                   :class="card.thumbnailUrl ? \'\' : \'module-card__thumb--placeholder thumb-\' + card.category">',
        '                <img v-if="card.thumbnailUrl" :src="card.thumbnailUrl" alt="" />',
        '                <i v-else :class="format().categoryIcon(card.category)" aria-hidden="true"></i>',
        '              </div>',
        '              <div class="module-card__body">',
        '                <h3 class="module-card__title">{{ card.title }}</h3>',
        '                <p class="clamp-2">{{ card.summary }}</p>',
        '                <div class="meta-row">',
        '                  <Tag :value="format().categoryLabel(card.category)"',
        '                       :severity="format().categorySeverity(card.category)" />',
        '                  <Tag :value="format().difficultyLabel(card.difficulty)"',
        '                       :severity="format().difficultySeverity(card.difficulty)" />',
        '                  <Chip :label="format().minutes(card.estimatedMinutes)" icon="pi pi-clock" />',
        '                </div>',
        '                <div v-if="card.enrollmentStatus !== \'NOT_ENROLLED\'" class="flex flex-column gap-1">',
        '                  <ProgressBar :value="card.progressPercent" :showValue="false" style="height:.5rem"',
        '                               :aria-label="card.progressPercent + \'% complete\'" />',
        '                  <span class="progress-caption">{{ format().lessonProgress(card) }}</span>',
        '                </div>',
        '                <div class="module-card__footer">',
        '                  <Button class="w-full" :label="format().actionLabel(card.enrollmentStatus)"',
        '                          icon="pi pi-arrow-right" iconPos="right" tabindex="-1"',
        '                          @click.stop="open(card)" />',
        '                </div>',
        '              </div>',
        '            </div>',
        '          </div>',
        '        </div>',
        '      </template>',

        '      <template #list="slotProps">',
        '        <div>',
        '          <div v-for="card in slotProps.items" :key="card.id" class="module-row" tabindex="0" role="link"',
        '               :aria-label="cardAriaLabel(card)"',
        '               @click="open(card)" @keydown.enter.prevent="open(card)"',
        '               @keydown.space.prevent="open(card)">',
        '            <div class="module-row__thumb"',
        '                 :class="card.thumbnailUrl ? \'\' : \'module-card__thumb--placeholder thumb-\' + card.category">',
        '              <img v-if="card.thumbnailUrl" :src="card.thumbnailUrl" alt="" />',
        '              <i v-else :class="format().categoryIcon(card.category)" aria-hidden="true"></i>',
        '            </div>',
        '            <div class="flex-1 flex flex-column gap-2">',
        '              <h3 class="module-card__title">{{ card.title }}</h3>',
        '              <p class="clamp-2">{{ card.summary }}</p>',
        '              <div class="meta-row">',
        '                <Tag :value="format().categoryLabel(card.category)"',
        '                     :severity="format().categorySeverity(card.category)" />',
        '                <Tag :value="format().difficultyLabel(card.difficulty)"',
        '                     :severity="format().difficultySeverity(card.difficulty)" />',
        '                <Chip :label="format().minutes(card.estimatedMinutes)" icon="pi pi-clock" />',
        '              </div>',
        '              <div v-if="card.enrollmentStatus !== \'NOT_ENROLLED\'" class="flex flex-column gap-1">',
        '                <ProgressBar :value="card.progressPercent" :showValue="false" style="height:.5rem"',
        '                             :aria-label="card.progressPercent + \'% complete\'" />',
        '                <span class="progress-caption">{{ format().lessonProgress(card) }}</span>',
        '              </div>',
        '            </div>',
        '            <Button :label="format().actionLabel(card.enrollmentStatus)" icon="pi pi-arrow-right"',
        '                    iconPos="right" tabindex="-1" @click.stop="open(card)" />',
        '          </div>',
        '        </div>',
        '      </template>',
        '    </DataView>',

        '  </main>',
        '</div>'
    ].join('\n')
};
