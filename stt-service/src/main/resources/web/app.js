const $ = id => document.getElementById(id);
const enroll = $('enroll'), finish = $('finish');
let stream, display, context, source, systemSource, worklet, sessionId, mode = 'idle', sequence = 0, closing = false;
let pending = Promise.resolve(), stopResolver, startTime = 0, polling = false;

async function request(path, body, headers = {}) {
  const response = await fetch(path, {
    method: body === undefined ? 'GET' : 'POST', body,
    headers: {...headers, ...(sessionId ? {'X-Session-Id': sessionId} : {})}
  });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || `HTTP ${response.status}`);
  return data;
}
function showError(message) {
  $('error').textContent = message;
  $('error').hidden = false;
}
function clearError() { $('error').hidden = true; }
function time(seconds) {
  const value = Math.floor(seconds);
  return `${String(Math.floor(value / 60)).padStart(2, '0')}:${String(value % 60).padStart(2, '0')}`;
}
function render(status) {
  const phase = status.phase || 'idle';
  $('phase').textContent = ({idle:'Ожидание', enrolling:'Запись эталона', preparing:'Создаём эталон',
    retry:'Повторите эталон', live:'Можно говорить · анализ идёт', stopping:'Завершаем анализ',
    finished:'Интервью завершено', error:'Ошибка обработки'})[phase] || phase;
  $('light').classList.toggle('active', phase === 'enrolling' || phase === 'live');
  $('role').textContent = phase === 'live' || phase === 'stopping' ? status.role || 'Ожидаем речь' : '—';
  $('audio-time').textContent = `${(status.audio_seconds || 0).toFixed(1).replace('.', ',')} с`;
  $('queue').textContent = `${status.queue_depth_chunks || 0} чанков`;
  if (status.output_dir) $('output').textContent = status.output_dir;
  if (status.error) showError(status.error);
  if (phase === 'retry') {
    enroll.disabled = false; enroll.textContent = 'Повторить запись голоса'; finish.disabled = true;
  } else if (phase === 'live') {
    enroll.disabled = true; finish.disabled = false;
    $('hint').textContent = 'Звук компьютера и микрофон анализируются вместе. Роль предварительная; результат сохранится в JSON.';
  } else if (phase === 'finished' || phase === 'error') {
    enroll.disabled = false; enroll.textContent = 'Новое интервью'; finish.disabled = true;
  }
}
function stopAudio() {
  if (!worklet || (mode !== 'enroll' && mode !== 'live')) return Promise.resolve();
  const ended = new Promise(resolve => { stopResolver = resolve; });
  worklet.port.postMessage('stop');
  return ended;
}
function releaseAudio() {
  for (const captured of [stream, display]) captured?.getTracks().forEach(track => {
    track.onended = null;
    track.stop();
  });
  source?.disconnect(); systemSource?.disconnect(); worklet?.disconnect(); context?.close();
  stream = display = context = source = systemSource = worklet = undefined;
}
function systemAudioAvailable() {
  return display?.getVideoTracks()[0]?.readyState === 'live' &&
    display?.getAudioTracks()[0]?.readyState === 'live';
}
async function sourceLost(reason) {
  if (closing || mode === 'done' || mode === 'idle') return;
  closing = true;
  showError(reason === 'system_audio_lost'
    ? 'Демонстрация экрана или системный звук прервались. Сохраняем обработанную часть интервью.'
    : 'Микрофон отключился. Сохраняем обработанную часть интервью.');
  try {
    if (mode === 'enroll' || mode === 'live') await stopAudio();
    mode = 'stopping';
    try { await pending; } catch (error) { showError(error.message); }
    if (sessionId) render(await request('/api/finish', new Uint8Array(), {'X-Stop-Reason': reason}));
  } catch (error) { showError(error.message); }
  finally { mode = 'done'; releaseAudio(); enroll.disabled = false; closing = false; }
}
async function prepareAudio() {
  // The display permission request must begin directly in the button click handler.
  const displayRequest = navigator.mediaDevices.getDisplayMedia({
    video: {displaySurface: 'monitor'}, audio: true, systemAudio: 'include', monitorTypeSurfaces: 'include'
  });
  display = await displayRequest;
  const video = display.getVideoTracks()[0], systemAudio = display.getAudioTracks()[0];
  if (video?.getSettings().displaySurface !== 'monitor')
    throw new Error('Выберите весь экран, а не окно или вкладку, и включите передачу системного звука.');
  if (!systemAudio || systemAudio.readyState !== 'live')
    throw new Error('Браузер не предоставил звук компьютера. Выберите весь экран со звуком в Chrome или Edge.');
  stream = await navigator.mediaDevices.getUserMedia({audio: {channelCount: 1, echoCancellation: false, noiseSuppression: false}});
  context = new AudioContext();
  await context.audioWorklet.addModule('/audio-worklet.js');
  source = context.createMediaStreamSource(stream);
  systemSource = context.createMediaStreamSource(new MediaStream([systemAudio]));
  worklet = new AudioWorkletNode(context, 'mono-pcm', {
    numberOfInputs: 2, numberOfOutputs: 1, outputChannelCount: [1]
  });
  systemSource.connect(worklet, 0, 0);
  source.connect(worklet, 0, 1);
  worklet.connect(context.destination);
  worklet.port.onmessage = event => {
    if (event.data.type === 'stopped') { stopResolver?.(); stopResolver = undefined; return; }
    if (event.data.type !== 'audio') return;
    const path = mode === 'enroll' ? '/api/enrollment/audio' : '/api/audio';
    const number = sequence++;
    pending = pending.then(() => request(path, event.data.buffer, {'X-Sequence': String(number)}));
    pending.catch(error => {
      showError(error.message);
      if (mode === 'live') sourceLost('capture_error');
    });
  };
  video.onended = systemAudio.onended = () => sourceLost('system_audio_lost');
  stream.getAudioTracks()[0].onended = () => sourceLost('microphone_lost');
  await context.resume();
  if (!systemAudioAvailable()) throw new Error('Демонстрация экрана или её аудиодорожка прервалась. Повторите выбор.');
}
async function startEnrollment() {
  clearError(); enroll.disabled = true; mode = 'opening'; sessionId = undefined;
  try {
    if (!navigator.mediaDevices?.getDisplayMedia || !navigator.mediaDevices?.getUserMedia)
      throw new Error('Откройте http://localhost:8765 в Chrome или Edge с поддержкой захвата экрана и микрофона.');
    // Both permissions are obtained before creating a model session.
    await prepareAudio();
    if (!systemAudioAvailable() || mode !== 'opening') throw new Error('Демонстрация экрана прервалась до начала записи.');
    const status = await request('/api/enrollment/start', new Uint8Array());
    if (!systemAudioAvailable()) {
      sessionId = status.session_id;
      await request('/api/finish', new Uint8Array(), {'X-Stop-Reason': 'system_audio_lost'});
      throw new Error('Системный звук прервался до записи эталона. Повторите выбор экрана.');
    }
    sessionId = status.session_id;
    pending = Promise.resolve(); sequence = 0; mode = 'enroll'; startTime = performance.now();
    worklet.port.postMessage('enroll');
    enroll.disabled = false; enroll.textContent = 'Завершить запись голоса';
    $('hint').textContent = 'Воспроизводите голос кандидата на компьютере около 10 секунд. Микрофон в эталон не входит.';
    render(status);
  } catch (error) {
    showError(error.message); releaseAudio(); mode = 'idle'; enroll.disabled = false;
  }
}
async function finishEnrollment() {
  enroll.disabled = true;
  try {
    await stopAudio(); mode = 'preparing'; await pending;
    if (!systemAudioAvailable()) { await sourceLost('system_audio_lost'); return; }
    const status = await request('/api/enrollment/finish', new Uint8Array());
    render(status);
    if (status.phase === 'retry') { mode = 'retry'; releaseAudio(); return; }
    if (status.phase !== 'live') throw new Error(status.error || 'Не удалось начать анализ.');
    pending = Promise.resolve(); sequence = 0; mode = 'live'; startTime = performance.now();
    worklet.port.postMessage('live');
  } catch (error) {
    showError(error.message);
    if (sessionId) try {
      await request('/api/finish', new Uint8Array(), {'X-Stop-Reason': 'capture_error'});
    } catch (_) { /* The original error is shown above. */ }
    releaseAudio(); mode = 'done'; enroll.disabled = false;
  }
}
async function finishInterview() {
  if (closing || mode !== 'live') return;
  closing = true; finish.disabled = true;
  try {
    await stopAudio(); mode = 'stopping'; await pending;
    render(await request('/api/finish', new Uint8Array(), {'X-Stop-Reason': 'requested'}));
  } catch (error) {
    showError(error.message);
    if (sessionId) try {
      await request('/api/finish', new Uint8Array(), {'X-Stop-Reason': 'capture_error'});
    } catch (_) { /* The original error is shown above. */ }
  }
  finally { releaseAudio(); closing = false; }
}
enroll.onclick = () => {
  if (mode === 'enroll') finishEnrollment();
  else if (mode === 'idle' || mode === 'retry' || mode === 'done') startEnrollment();
};
finish.onclick = finishInterview;
setInterval(async () => {
  if (mode === 'enroll' || mode === 'live') $('timer').textContent = time((performance.now() - startTime) / 1000);
  if (polling) return;
  polling = true;
  try {
    const status = await request('/api/status');
    if (mode !== 'opening' && status.session_id && status.session_id === sessionId) {
      render(status);
      if ((status.phase === 'error' || status.phase === 'finished') && mode === 'live') {
        mode = 'stopping'; releaseAudio();
      }
      if (status.phase === 'finished' || status.phase === 'error') mode = 'done';
      if (status.phase === 'retry') mode = 'retry';
    }
  } catch (error) { showError(error.message); }
  finally { polling = false; }
}, 500);
