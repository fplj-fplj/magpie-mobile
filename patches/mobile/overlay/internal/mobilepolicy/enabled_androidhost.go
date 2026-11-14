//go:build androidhost && !mobile

package mobilepolicy

// Android keeps process ownership and authentication, with the official API surface.
const Enabled = true
const FullAdmin = true
