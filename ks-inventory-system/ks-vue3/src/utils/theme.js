export function normalizeTheme(theme) {
	return /^#[\da-f]{6}$/i.test(theme || '') ? theme : '#167D78'
}

// Keep the user's hue; derive readable accents separately for each surface mode.
export function createThemePalette(theme, isDark) {
	const preferred = normalizeTheme(theme)
	const surface = isDark ? '#182e34' : '#ffffff'
	const requested = isDark && preferred.toLowerCase() === '#167d78' ? '#72bfb7' : preferred
	const primary = readableColor(requested, surface, isDark ? '#ffffff' : '#152c32')
	let soft = surface
	for (let step = 0; step <= 9; step++) {
		soft = mixColor(primary, surface, .91 + step / 100)
		if (contrastRatio(primary, soft) >= 4.5) break
	}
	const onPrimary = contrastRatio(primary, '#102126') >= contrastRatio(primary, '#ffffff') ? '#102126' : '#ffffff'
	return {
		primary,
		soft,
		hover: readableColor(isDark ? getLightColor(primary, .15) : getDarkColor(primary, .18), onPrimary, onPrimary === '#ffffff' ? '#152c32' : '#ffffff'),
		onPrimary,
		tints: Array.from({ length: 9 }, (_, index) => mixColor(primary, surface, (index + 1) / 10))
	}
}

export function handleThemeStyle(theme) {
	const root = document.documentElement
	// Old inline semantic variables would override html.dark after a mode change.
	for (const name of ['--sc-primary', '--sc-primary-soft', '--sc-primary-hover', '--el-color-primary']) root.style.removeProperty(name)
	for (let i = 1; i <= 9; i++) {
		root.style.removeProperty(`--el-color-primary-light-${i}`)
		root.style.removeProperty(`--el-color-primary-dark-${i}`)
	}
	for (const mode of ['light', 'dark']) {
		const palette = createThemePalette(theme, mode === 'dark')
		for (const [name, value] of Object.entries({ primary: palette.primary, soft: palette.soft, hover: palette.hover, foreground: palette.onPrimary })) root.style.setProperty(`--sc-accent-${mode}-${name}`, value)
		palette.tints.forEach((color, index) => root.style.setProperty(`--sc-accent-${mode}-tint-${index + 1}`, color))
	}
	const themeMeta = document.querySelector('meta[name="theme-color"]')
	if (themeMeta) themeMeta.content = root.classList.contains('dark') ? '#102126' : '#f6f8f9'
}

function luminance(color) {
	const channels = hexToRgb(color).map(value => {
		const channel = value / 255
		return channel <= .04045 ? channel / 12.92 : ((channel + .055) / 1.055) ** 2.4
	})
	return channels[0] * .2126 + channels[1] * .7152 + channels[2] * .0722
}

function contrastRatio(first, second) {
	const values = [luminance(first), luminance(second)].sort((a, b) => b - a)
	return (values[0] + .05) / (values[1] + .05)
}

function readableColor(color, background, target, minimum = 4.5) {
	for (let step = 0; step <= 40; step++) {
		const candidate = mixColor(color, target, step / 40)
		if (contrastRatio(candidate, background) >= minimum) return candidate
	}
	return target
}

function mixColor(color, background, weight) {
	const foreground = hexToRgb(color)
	const base = hexToRgb(background)
	return rgbToHex(...foreground.map((channel, index) => Math.round(channel * (1 - weight) + base[index] * weight)))
}

// hex颜色转rgb颜色
export function hexToRgb(str) {
	str = str.replace('#', '')
	let hexs = str.match(/../g)
	for (let i = 0; i < 3; i++) {
		hexs[i] = parseInt(hexs[i], 16)
	}
	return hexs
}

// rgb颜色转Hex颜色
export function rgbToHex(r, g, b) {
	let hexs = [r.toString(16), g.toString(16), b.toString(16)]
	for (let i = 0; i < 3; i++) {
		if (hexs[i].length == 1) {
			hexs[i] = `0${hexs[i]}`
		}
	}
	return `#${hexs.join('')}`
}

// 变浅颜色值
export function getLightColor(color, level) {
	let rgb = hexToRgb(color)
	for (let i = 0; i < 3; i++) {
		rgb[i] = Math.floor((255 - rgb[i]) * level + rgb[i])
	}
	return rgbToHex(rgb[0], rgb[1], rgb[2])
}

// 变深颜色值
export function getDarkColor(color, level) {
	let rgb = hexToRgb(color)
	for (let i = 0; i < 3; i++) {
		rgb[i] = Math.floor(rgb[i] * (1 - level))
	}
	return rgbToHex(rgb[0], rgb[1], rgb[2])
}
