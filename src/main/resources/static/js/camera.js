// Camera input. Live barcode scanning where the browser can do it (Chrome/Android),
// otherwise a photo that the server decodes with ZXing.

export const liveBarcodeSupported = async () => {
  if (!('BarcodeDetector' in window) || !navigator.mediaDevices?.getUserMedia) return false;
  try {
    return (await BarcodeDetector.getSupportedFormats()).includes('ean_13');
  } catch { return false; }
};

/**
 * Runs the camera in `video`. Where the browser can detect barcodes, every new ISBN-like EAN-13 goes to onIsbn
 * while the camera keeps running, so one book after another can be scanned. shoot() takes a still photo.
 * Returns { stop, shoot }; onError gets camera failures (no permission, no camera).
 */
export function liveCamera(video, { onIsbn, onError, onCodes }) {
  let stream, timer, stopped = false, last = null;
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
    if (!await liveBarcodeSupported()) return;
    const detector = new BarcodeDetector({ formats: ['ean_13'] });
    timer = setInterval(async () => {
      try {
        const codes = (await detector.detect(video)).filter(c => /^97[89]\d{10}$/.test(c.rawValue));
        onCodes?.(codes);
        const hit = codes[0]?.rawValue;
        if (hit && hit !== last) { last = hit; onIsbn(hit); }
      } catch { /* frame not ready */ }
    }, 120);
  })().catch(err => { stop(); onError?.(err); });
  return { stop, shoot: () => grab(stream, video) };
}

export const cameraAvailable = () => !!navigator.mediaDevices?.getUserMedia;

/**
 * JPEG photos up to 15 MB go up untouched: decoding a 12 MP photo in the browser needs ~100 MB of RAM,
 * which crashes tabs on phones short of memory. The server turns (EXIF) and scales them anyway.
 * Other formats or huge files are re-encoded here.
 */
export async function prepareImage(file, maxEdge = 4000) {
  if (file.type === 'image/jpeg' && file.size <= 15 * 1024 * 1024 && maxEdge >= 4000) {
    return file;
  }
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

/** Opens the gallery / file picker and resolves with the chosen file (or null). */
export async function pickPhoto() {
  return (await pickPhotos(false))[0] ?? null;
}

/** Several photos from the gallery at once, e.g. old pictures of shelves to import. */
export function pickPhotos(multiple = true) {
  return new Promise(resolve => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'image/*';
    input.multiple = multiple;
    input.addEventListener('change', () => resolve([...(input.files ?? [])]), { once: true });
    input.addEventListener('cancel', () => resolve([]), { once: true });
    input.click();
  });
}

/**
 * Draws a frame exactly around a barcode in the live picture, perspective included, and lets a reading beam run
 * over it. The detector reports the four corners every 120 ms; the frame glides between them every frame.
 * lock() marks the moment the ISBN is taken.
 */
export function barcodeTracker(video, svg) {
  const poly = svg.querySelector('polygon');
  const beam = svg.querySelector('line');
  const still = matchMedia('(prefers-reduced-motion: reduce)').matches;
  let target = null, current = null, seen = 0, raf;
  const lerp = (a, b, k) => ({ x: a.x + (b.x - a.x) * k, y: a.y + (b.y - a.y) * k });
  const frame = t => {
    const w = svg.clientWidth, h = svg.clientHeight;
    svg.setAttribute('viewBox', `0 0 ${w} ${h}`);
    const visible = target && performance.now() - seen < 500;
    svg.classList.toggle('seen', !!visible);
    if (target) {
      current = !current || still ? target.map(p => ({ ...p })) : current.map((p, i) => lerp(p, target[i], 0.35));
      poly.setAttribute('points', current.map(p => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' '));
      const k = still ? 0.5 : (Math.sin(t / 240) + 1) / 2;
      const a = lerp(current[0], current[3], k), b = lerp(current[1], current[2], k);
      beam.setAttribute('x1', a.x); beam.setAttribute('y1', a.y); beam.setAttribute('x2', b.x); beam.setAttribute('y2', b.y);
    }
    raf = requestAnimationFrame(frame);
  };
  raf = requestAnimationFrame(frame);
  return {
    update(codes) {
      const corners = codes[0]?.cornerPoints;
      if (!corners || corners.length !== 4 || !video.videoWidth) return;
      // the video is cropped to fill its box (object-fit: cover): same scale and offset as the browser uses
      const w = svg.clientWidth, h = svg.clientHeight;
      const s = Math.max(w / video.videoWidth, h / video.videoHeight);
      const ox = (w - video.videoWidth * s) / 2, oy = (h - video.videoHeight * s) / 2;
      const pts = corners.map(p => ({ x: p.x * s + ox, y: p.y * s + oy }));
      const c = pts.reduce((m, p) => ({ x: m.x + p.x / 4, y: m.y + p.y / 4 }), { x: 0, y: 0 });
      target = pts.map(p => lerp(c, p, 1.18)); // a little air around the bars
      seen = performance.now();
    },
    lock() {
      svg.classList.remove('locked');
      void svg.getBoundingClientRect(); // restart the animation for the next book
      svg.classList.add('locked');
      setTimeout(() => svg.classList.remove('locked'), 1200);
    },
    stop() { cancelAnimationFrame(raf); },
  };
}

/**
 * Takes a photo with an in-page camera. Switching to the camera app lets Android kill the browser,
 * and the page (with the photo) is gone when it comes back. Resolves with a Blob/File, or null if cancelled.
 */
export function capturePhoto(hint = '') {
  if (!navigator.mediaDevices?.getUserMedia) return pickPhoto();
  return new Promise(resolve => {
    const overlay = document.createElement('div');
    overlay.className = 'camera';
    overlay.innerHTML = `
      <video playsinline muted></video>
      <p class="camera-hint"></p>
      <div class="camera-bar">
        <button class="camera-side" data-act="cancel">Abbrechen</button>
        <button class="camera-shutter" data-act="shoot" aria-label="Foto aufnehmen" disabled></button>
        <button class="camera-side" data-act="gallery">Galerie</button>
      </div>
      <button class="camera-torch" data-act="torch" hidden>Licht</button>`;
    overlay.querySelector('.camera-hint').textContent = hint;
    document.body.append(overlay);
    const video = overlay.querySelector('video');
    let stream, torch = false;
    const close = result => {
      stream?.getTracks().forEach(t => t.stop());
      overlay.remove();
      resolve(result);
    };
    overlay.addEventListener('click', async e => {
      const act = e.target.closest('[data-act]')?.dataset.act;
      if (act === 'cancel') close(null);
      if (act === 'gallery') { stream?.getTracks().forEach(t => t.stop()); overlay.remove(); resolve(await pickPhoto()); }
      if (act === 'torch') {
        torch = !torch;
        await stream.getVideoTracks()[0].applyConstraints({ advanced: [{ torch }] }).catch(() => {});
        e.target.classList.toggle('on', torch);
      }
      if (act === 'shoot') {
        e.target.disabled = true;
        close(await grab(stream, video));
      }
    });
    navigator.mediaDevices.getUserMedia({
      video: { facingMode: 'environment', width: { ideal: 4096 }, height: { ideal: 3072 } }, audio: false,
    }).then(async s => {
      stream = s;
      video.srcObject = s;
      await video.play();
      overlay.querySelector('[data-act="shoot"]').disabled = false;
      const caps = s.getVideoTracks()[0].getCapabilities?.() ?? {};
      if (caps.torch) overlay.querySelector('[data-act="torch"]').hidden = false;
    }).catch(async () => {
      // no permission or no camera: fall back to the file picker
      overlay.remove();
      resolve(await pickPhoto());
    });
  });
}

async function grab(stream, video) {
  const track = stream.getVideoTracks()[0];
  if ('ImageCapture' in window) {
    try {
      return await new ImageCapture(track).takePhoto(); // full sensor resolution where supported
    } catch { /* fall through to a video frame */ }
  }
  const canvas = document.createElement('canvas');
  canvas.width = video.videoWidth;
  canvas.height = video.videoHeight;
  canvas.getContext('2d').drawImage(video, 0, 0);
  return new Promise(r => canvas.toBlob(r, 'image/jpeg', 0.92));
}
