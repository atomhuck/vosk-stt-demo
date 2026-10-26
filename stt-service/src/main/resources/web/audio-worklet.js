class MonoPcmWorklet extends AudioWorkletProcessor {
  constructor() {
    super();
    this.active = false;
    this.phase = 'enroll';
    this.output = new Int16Array(10240);
    this.used = 0;
    this.inputIndex = 0;
    this.nextOutput = 0;
    this.previous = 0;
    this.step = sampleRate / 16000;
    this.port.onmessage = event => {
      if (event.data === 'enroll' || event.data === 'live') {
        this.phase = event.data;
        this.active = true;
        this.used = 0;
        this.inputIndex = 0;
        this.nextOutput = 0;
        this.previous = 0;
      } else if (event.data === 'stop') {
        this.active = false;
        if (this.used) this.send();
        this.port.postMessage({type: 'stopped'});
      }
    };
  }
  send() {
    const buffer = this.output.buffer.slice(0, this.used * 2);
    this.port.postMessage({type: 'audio', buffer}, [buffer]);
    this.used = 0;
  }
  mono(channels, index) {
    if (!channels.length) return 0;
    let total = 0;
    for (const channel of channels) total += channel[index] || 0;
    return total / channels.length;
  }
  process(inputs, outputs) {
    const system = inputs[0] || [];
    const microphone = inputs[1] || [];
    if (this.active) {
      const frames = system[0]?.length || microphone[0]?.length || 128;
      for (let i = 0; i < frames; i++) {
        const computer = this.mono(system, i);
        const current = this.phase === 'enroll' ? computer :
          Math.max(-1, Math.min(1, (computer + this.mono(microphone, i)) * .5));
        while (this.nextOutput <= this.inputIndex) {
          const fraction = this.inputIndex ? Math.max(0, Math.min(1, this.nextOutput - (this.inputIndex - 1))) : 1;
          const value = this.previous + (current - this.previous) * fraction;
          this.output[this.used++] = Math.max(-32768, Math.min(32767, Math.round(value * 32767)));
          if (this.used === this.output.length) this.send();
          this.nextOutput += this.step;
        }
        this.previous = current;
        this.inputIndex++;
      }
    }
    for (const output of outputs) for (const ch of output) ch.fill(0);
    return true;
  }
}
registerProcessor('mono-pcm', MonoPcmWorklet);
