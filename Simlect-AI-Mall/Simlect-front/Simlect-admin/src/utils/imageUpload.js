
export const MAX_UPLOAD_BYTES = 5 * 1024 * 1024

const loadImageFromBlob = (blob) =>
  new Promise((resolve, reject) => {
    const url = URL.createObjectURL(blob)
    const img = new Image()
    img.onload = () => {
      URL.revokeObjectURL(url)
      resolve(img)
    }
    img.onerror = () => {
      URL.revokeObjectURL(url)
      reject(new Error('图片解码失败'))
    }
    img.src = url
  })

const canvasToBlob = (canvas, type, quality) =>
  new Promise((resolve, reject) => {
    canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new Error('图片导出失败'))), type, quality)
  })

const drawScaled = (img, maxEdge) => {
  let w = img.width
  let h = img.height
  const scale = Math.min(1, maxEdge / Math.max(w, h, 1))
  w = Math.max(1, Math.round(w * scale))
  h = Math.max(1, Math.round(h * scale))
  const canvas = document.createElement('canvas')
  canvas.width = w
  canvas.height = h
  const ctx = canvas.getContext('2d')
  ctx.drawImage(img, 0, 0, w, h)
  return { canvas, w, h }
}

const encodeUnderLimit = async (canvas, maxBytes) => {
  let quality = 0.92
  let blob = await canvasToBlob(canvas, 'image/jpeg', quality)
  while (blob.size > maxBytes && quality > 0.35) {
    quality -= 0.07
    blob = await canvasToBlob(canvas, 'image/jpeg', quality)
  }
  return blob
}


export async function prepareImageForUpload(input, opts = {}) {
  const maxBytes = opts.maxBytes ?? MAX_UPLOAD_BYTES
  const maxEdge = opts.maxEdge ?? 4096
  const img = await loadImageFromBlob(input)
  let { canvas, w, h } = drawScaled(img, maxEdge)
  let blob = await encodeUnderLimit(canvas, maxBytes)
  if (blob.size <= maxBytes) return blob

  let shrink = 0.85
  while (blob.size > maxBytes && shrink >= 0.35) {
    w = Math.max(1, Math.round(w * shrink))
    h = Math.max(1, Math.round(h * shrink))
    canvas.width = w
    canvas.height = h
    const ctx = canvas.getContext('2d')
    ctx.drawImage(img, 0, 0, w, h)
    blob = await encodeUnderLimit(canvas, maxBytes)
    shrink -= 0.1
  }

  if (blob.size > maxBytes) {
    throw new Error('图片过大，请换一张更小的图片')
  }
  return blob
}
