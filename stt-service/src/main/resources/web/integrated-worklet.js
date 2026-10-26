class MonoPcmWorklet extends AudioWorkletProcessor {
  constructor() {
    super();
    this.active = false;
    this.phase = 'enroll';
    // 160 ms frames (four are batched by Java into one 640 ms Nemotron input).
    this.output = new Int16Array(2560);
    this.used = 0;
    this.inputIndex = 0;
    this.nextOutput = 0;
    this.previousInput = 0;
    this.step = sampleRate / 16000;
    this.port.onmessage = event => {
      if (event.data === 'enroll' || event.data === 'live') {
        this.phase = event.data;
        this.active = true;
        this.used = 0;
        this.inputIndex = 0;
        this.nextOutput = 0;
        this.previousInput = 0;
      } else if (event.data === 'stop') {
        this.active = false;
        if (this.used) this.send();
        this.port.postMessage({type: 'stopped'});
      }
    };
  }
  send() {
    let buffer;
    buffer = this.output.buffer.slice(0, this.used * 2);
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
        const mic = this.mono(microphone, i);
        const computer = this.mono(system, i);
        // Enrollment and interview use the same mixed mono signal. Source identity
        // is discarded before audio reaches Java or any model.
        const value = (mic + computer) * 0.5;
        while (this.nextOutput <= this.inputIndex) {
          const fraction = this.inputIndex ? Math.max(0, Math.min(1, this.nextOutput - (this.inputIndex - 1))) : 1;
          const sample = this.previousInput + (value - this.previousInput) * fraction;
          this.output[this.used] = Math.max(-32768, Math.min(32767, Math.round(sample * 32767)));
          this.used++;
          if (this.used === this.output.length) this.send();
          this.nextOutput += this.step;
        }
        this.previousInput = value;
        this.inputIndex++;
      }
    }
    for (const output of outputs) for (const ch of output) ch.fill(0);
    return true;
  }
}
registerProcessor('mono-pcm', MonoPcmWorklet);
