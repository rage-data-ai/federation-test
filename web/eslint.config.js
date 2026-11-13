import pluginVue from 'eslint-plugin-vue'

export default [
  ...pluginVue.configs['flat/essential'],
  {
    files: ['src/**/*.{js,vue}'],
    rules: {
      'vue/multi-word-component-names': 'off',
      'no-unused-vars': ['warn', { argsIgnorePattern: '^_' }]
    }
  }
]
