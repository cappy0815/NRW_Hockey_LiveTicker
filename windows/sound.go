//go:build windows

// Toene wie in v2.0: beim ersten Start synthetisch erzeugt (Ordner "sounds" unter %APPDATA%\EHV-Live-Ticker).
// Vorhandene WAV-Dateien (auch eigene) werden nicht ueberschrieben.
package main

import (
	"encoding/binary"
	"math"
	"math/rand"
	"os"
	"unsafe"
)

const sndRate = 16000

var sndDir string

func soundPlay(name string) {
	on := map[string]bool{"tor": optSndTor, "strafe": optSndStrafe, "drittel": optSndDrittel, "spielende": optSndEnde, "bully": optSndBully}
	if on[name] {
		soundPlayFile(name)
	}
}

// ohne Pruefung der Optionen (Probehoeren)
func soundPlayFile(name string) {
	f := sndDir + name + ".wav"
	if _, err := os.Stat(f); err == nil {
		pPlaySoundW.Call(uintptr(unsafe.Pointer(u16(f))), 0, 0x20000|0x1|0x2) // SND_FILENAME|SND_ASYNC|SND_NODEFAULT
	}
}

func soundsEnsure() {
	os.MkdirAll(sndDir, 0755)
	mk := func(name string, gen func() []float64) {
		f := sndDir + name + ".wav"
		if _, err := os.Stat(f); err == nil {
			return
		}
		wavWrite(f, gen())
	}
	mk("tor", synHorn)
	mk("drittel", synBuzzer)
	mk("strafe", func() []float64 { b := make([]float64, int(0.85*sndRate)); synWhistle(b, 0, 0.85); return b })
	mk("spielende", func() []float64 { // kurz - kurz - laaang
		b := make([]float64, int(1.68*sndRate))
		synWhistle(b, 0, 0.22)
		synWhistle(b, int(0.34*sndRate), 0.22)
		synWhistle(b, int(0.68*sndRate), 1.0)
		return b
	})
	mk("bully", synChime)
}

func env(i, n int, att, rel float64) float64 {
	a, r := int(att*sndRate), int(rel*sndRate)
	if i < a {
		return float64(i) / float64(a)
	}
	if i > n-r {
		return float64(n-i) / float64(r)
	}
	return 1
}

// Torhorn: D-Dur-Akkord mit Obertoenen, weich gesaettigt
func synHorn() []float64 {
	n := int(2.0 * sndRate)
	b := make([]float64, n)
	fr := []float64{146.83, 185.0, 220.0}
	for i := range b {
		t := float64(i) / sndRate
		v := 0.0
		for _, f := range fr {
			for h := 1.0; h <= 5; h++ {
				v += math.Sin(2*math.Pi*f*h*t) / h
			}
		}
		v *= 0.9
		b[i] = v / (1 + math.Abs(v)) * env(i, n, 0.04, 0.30) * 0.85
	}
	return b
}

// Sirene/Buzzer: 110 Hz mit ungeraden Obertoenen
func synBuzzer() []float64 {
	n := int(1.4 * sndRate)
	b := make([]float64, n)
	for i := range b {
		t := float64(i) / sndRate
		v := 0.0
		for _, h := range []float64{1, 3, 5, 7, 9} {
			v += math.Sin(2*math.Pi*110*h*t) / h
		}
		v *= 1.5
		b[i] = v / (1 + math.Abs(v)) * env(i, n, 0.02, 0.12)
	}
	return b
}

// Schiri-Trillerpfeife: ~2900 Hz, mit 28 Hz getrillert, etwas Atemrauschen
func synWhistle(b []float64, start int, dur float64) {
	n := int(dur * sndRate)
	ph := 0.0
	for i := 0; i < n && start+i < len(b); i++ {
		t := float64(i) / sndRate
		tr := math.Sin(2 * math.Pi * 28 * t)
		ph += 2 * math.Pi * (2900 + 140*tr) / sndRate
		v := math.Sin(ph)*(0.78+0.22*tr) + (rand.Float64()*2-1)*0.04
		b[start+i] = v * env(i, n, 0.015, 0.06) * 0.55
	}
}

// Gong: sanftes "Ding-Ding" (C6 -> E6)
func synChime() []float64 {
	n := int(1.25 * sndRate)
	b := make([]float64, n)
	fr, st, am := []float64{1046.5, 1318.5}, []float64{0, 0.16}, []float64{0.30, 0.26}
	fade := int(0.08 * sndRate)
	for i := range b {
		t := float64(i) / sndRate
		v := 0.0
		for k := 0; k < 2; k++ {
			tn := t - st[k]
			if tn < 0 {
				continue
			}
			e := math.Exp(-tn * 4.2)
			if tn < 0.006 {
				e *= tn / 0.006
			}
			v += am[k] * e * (math.Sin(2*math.Pi*fr[k]*tn) + 0.18*math.Sin(2*math.Pi*2*fr[k]*tn))
		}
		if i > n-fade {
			v *= float64(n-i) / float64(fade)
		}
		b[i] = v * 0.9
	}
	return b
}

// 16-Bit-Mono-PCM als WAV
func wavWrite(file string, s []float64) {
	data := make([]byte, 44+2*len(s))
	copy(data[0:], "RIFF")
	binary.LittleEndian.PutUint32(data[4:], uint32(36+2*len(s)))
	copy(data[8:], "WAVEfmt ")
	binary.LittleEndian.PutUint32(data[16:], 16)
	binary.LittleEndian.PutUint16(data[20:], 1)
	binary.LittleEndian.PutUint16(data[22:], 1)
	binary.LittleEndian.PutUint32(data[24:], sndRate)
	binary.LittleEndian.PutUint32(data[28:], sndRate*2)
	binary.LittleEndian.PutUint16(data[32:], 2)
	binary.LittleEndian.PutUint16(data[34:], 16)
	copy(data[36:], "data")
	binary.LittleEndian.PutUint32(data[40:], uint32(2*len(s)))
	for i, v := range s {
		if v > 1 {
			v = 1
		} else if v < -1 {
			v = -1
		}
		binary.LittleEndian.PutUint16(data[44+2*i:], uint16(int16(v*32767)))
	}
	os.WriteFile(file, data, 0644)
}
