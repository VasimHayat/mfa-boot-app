/* Light/dark switch. The choice is persisted in localStorage and applied before Vue mounts. */
window.ThemeToggle = {
    data: function () {
        return { theme: 'light' };
    },
    created: function () {
        this.theme = window.App.Theme.current();
    },
    computed: {
        isDark: function () {
            return this.theme === 'dark';
        },
        label: function () {
            return this.isDark ? 'Switch to light theme' : 'Switch to dark theme';
        }
    },
    methods: {
        toggle: function () {
            this.theme = this.isDark ? 'light' : 'dark';
            window.App.Theme.apply(this.theme);
        }
    },
    template: [
        '<Button type="button" text rounded',
        '        :icon="isDark ? \'pi pi-sun\' : \'pi pi-moon\'"',
        '        :aria-label="label"',
        '        :aria-pressed="isDark"',
        '        v-tooltip.bottom="label"',
        '        @click="toggle" />'
    ].join('\n')
};
