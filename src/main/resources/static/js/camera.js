// Camera input. Live barcode scanning where the browser can do it (Chrome/Android),
// otherwise a photo that the server decodes with ZXing.

export const liveBarcodeSupported = async () => {
  if (!('BarcodeDetector' in window) || !navigator.mediaDevices?.getUserMedia) return false;
  try {
    return (await BarcodeDetector.getSupportedFormats()).includes('ean_13');
  } catch { return false; }
};

/** Starts the camera in `video` and resolves with the first ISBN-like EAN-13. Call the returned stop() to abort. */
export function scanBarcode(video, onIsbn) {
  let stream, timer, stopped = false;
  const stop = () => {
    stopped = true;
    clearInterval(timer);
    stream?.getTracks().forEach(t => t.stop());
  };
  (async () => {
    stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment', width: { ideal: 1920 } }, audio: false });
    if (stopped) { stop(); return; }
    video.srcObject = stream;
    video.setAttribute('playsinline', '');
    video.muted = true;
    await video.play();
    const detector = new BarcodeDetector({ formats: ['ean_13'] });
    timer = setInterval(async () => {
      try {
        const codes = await detector.detect(video);
        const hit = codes.map(c => c.rawValue).find(v => /^97[89]\d{10}$/.test(v));
        if (hit) { stop(); onIsbn(hit); }
      } catch { /* frame not ready */ }
    }, 250);
  })().catch(err => { stop(); onIsbn(null, err); });
  return stop;
}

/** Downscales a phone photo before upload: keeps enough detail for spines, saves bandwidth. */
export async function prepareImage(file, maxEdge = 4000) {
  const bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' });
  const scale = Math.min(1, maxEdge / Math.max(bitmap.width, bitmap.height));
  const canvas = document.createElement('canvas');
  canvas.width = Math.round(bitmap.width * scale);
  canvas.height = Math.round(bitmap.height * scale);
  canvas.getContext('2d').drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  bitmap.close?.();
  return new Promise((resolve, reject) =>
    canvas.toBlob(b => b ? resolve(b) : reject(new Error('Bild konnte nicht verarbeitet werden')), 'image/jpeg', 0.9));
}

/** Opens the native camera/file picker and resolves with the chosen file (or null). */
export function pickPhoto() {
  return new Promise(resolve => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'image/*';
    input.capture = 'environment';
    input.addEventListener('change', () => resolve(input.files?.[0] ?? null), { once: true });
    input.click();
  });
}
