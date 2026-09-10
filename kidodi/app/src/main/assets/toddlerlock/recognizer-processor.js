// AudioWorklet that forwards mic samples to the Vosk recognizer (runs off the main
// thread). Verbatim from the vosk-browser modern-vanilla example. Loaded via
// audioWorklet.addModule(), never as a normal <script>.
class RecognizerAudioProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super(options);
    this.port.onmessage = this._processMessage.bind(this);
  }

  _processMessage(event) {
    if (event.data.action === "init") {
      this._recognizerId = event.data.recognizerId;
      this._recognizerPort = event.ports[0];
    }
  }

  process(inputs) {
    const data = inputs[0][0];
    if (this._recognizerPort && data) {
      // Float32 [-1,1] -> int16 range that Kaldi expects.
      const audioArray = data.map((value) => value * 0x8000);
      this._recognizerPort.postMessage(
        { action: "audioChunk", data: audioArray, recognizerId: this._recognizerId, sampleRate },
        { transfer: [audioArray.buffer] }
      );
    }
    return true;
  }
}

registerProcessor("recognizer-processor", RecognizerAudioProcessor);
