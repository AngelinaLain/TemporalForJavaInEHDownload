export const defaultSearchOptions = () => ({
  keyword: '', filterCats: 0, language: null, minimumRating: 1,
  pageAtLeast: null, pageAtMost: null, searchExpungedGalleries: false,
  showOnlyWithTorrents: false, disableLanguageFilter: false,
  disableUploaderFilter: false, disableTagsFilter: false
})

export const buildSearchOptions = (form, tags = []) => ({
  ...defaultSearchOptions(), ...form,
  keyword: [...tags, (form.keyword || '').trim()].filter(Boolean).join(' ').trim()
})
