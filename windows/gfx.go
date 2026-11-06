//go:build windows

// Zeichnen: alles selbst gemalt (GDI fuer Text, GDI+ fuer runde Formen und Logos), doppelt gepuffert.
// Koordinaten sind logische Pixel (96 dpi) und werden mit sc auf den Bildschirm skaliert.
package main

import (
	"math"
	"syscall"
	"unsafe"
)

var sc = 1.0 // Skalierung (Windows-Anzeigeeinstellung 100 % = 1.0)

func S(v int) int32 { return int32(math.Round(float64(v) * sc)) }
func L(v int32) int { return int(math.Round(float64(v) / sc)) } // physisch -> logisch

// Farben als 0xRRGGBB
const (
	cDark    = 0x22262B
	cDark2   = 0x343A42
	cGreen   = 0x008C45
	cRed     = 0xC8102E
	cGreyTxt = 0x6E747C
	cLine    = 0xE1E4E8
	cBg      = 0xF4F5F7
	cWhite   = 0xFFFFFF
	cFav     = 0xFFF6D6
	cSel     = 0xE3EEFF
	cHover   = 0xEEF0F3
	cTickBg  = 0x1B1E22
	cTickBox = 0x262A30
	cBlue    = 0x7FD3FF
	cGoal    = 0xFFD24A
)

func cref(c uint32) uintptr { return uintptr((c&0xFF)<<16 | (c & 0xFF00) | (c>>16)&0xFF) }
func argb(c uint32) uintptr { return uintptr(0xFF000000 | c) }

func initDPI() {
	// System-DPI-bewusst: scharfe Schrift bei 125 %/150 % Skalierung
	if pSetProcessDpiAwareCtx.Find() == nil {
		pSetProcessDpiAwareCtx.Call(^uintptr(1)) // DPI_AWARENESS_CONTEXT_SYSTEM_AWARE = -2
	} else if pSetProcessDPIAware.Find() == nil {
		pSetProcessDPIAware.Call()
	}
	dc, _, _ := pGetDC.Call(0)
	dpi, _, _ := pGetDeviceCaps.Call(dc, 88) // LOGPIXELSX
	pReleaseDC.Call(0, dc)
	if dpi >= 96 {
		sc = float64(dpi) / 96
	}
	var tok uintptr
	in := struct {
		ver  uint32
		cb   uintptr
		sup1 int32
		sup2 int32
	}{ver: 1}
	pGdiplusStartup.Call(uintptr(unsafe.Pointer(&tok)), uintptr(unsafe.Pointer(&in)), 0)
}

// ---------------------------------------------------------------- Schriften
type fontKey struct {
	size10 int
	bold   bool
	face   string
}

var fonts = map[fontKey]uintptr{}

func font(pt float64, bold bool, face string) uintptr {
	k := fontKey{int(pt * 10), bold, face}
	if f, ok := fonts[k]; ok {
		return f
	}
	h := -int32(math.Round(pt * 96 * sc / 72))
	w := uintptr(400)
	if bold {
		w = 700
	}
	f, _, _ := pCreateFontW.Call(uintptr(h), 0, 0, 0, w, 0, 0, 0, 1, 0, 0, 5, 0, uintptr(unsafe.Pointer(u16(face))))
	fonts[k] = f
	return f
}

func ui(pt float64, bold bool) uintptr { return font(pt, bold, "Segoe UI") }

// ---------------------------------------------------------------- Leinwand
type canvas struct{ dc uintptr }

func paintWindow(hwnd uintptr, draw func(c *canvas, w, h int)) {
	var ps paintStruct
	hdc, _, _ := pBeginPaint.Call(hwnd, uintptr(unsafe.Pointer(&ps)))
	var r rect
	pGetClientRect.Call(hwnd, uintptr(unsafe.Pointer(&r)))
	mdc, _, _ := pCreateCompatibleDC.Call(hdc)
	bmp, _, _ := pCreateCompatibleBmp.Call(hdc, uintptr(r.right), uintptr(r.bottom))
	old, _, _ := pSelectObject.Call(mdc, bmp)
	pSetBkMode.Call(mdc, 1) // TRANSPARENT
	draw(&canvas{mdc}, L(r.right), L(r.bottom))
	pBitBlt.Call(hdc, 0, 0, uintptr(r.right), uintptr(r.bottom), mdc, 0, 0, 0xCC0020)
	pSelectObject.Call(mdc, old)
	pDeleteObject.Call(bmp)
	pDeleteDC.Call(mdc)
	pEndPaint.Call(hwnd, uintptr(unsafe.Pointer(&ps)))
}

func (c *canvas) rect(x, y, w, h int, col uint32) {
	r := rect{S(x), S(y), S(x + w), S(y + h)}
	br, _, _ := pCreateSolidBrush.Call(cref(col))
	pFillRect.Call(c.dc, uintptr(unsafe.Pointer(&r)), br)
	pDeleteObject.Call(br)
}

func (c *canvas) gp() uintptr {
	var g uintptr
	pGdipCreateFromHDC.Call(c.dc, uintptr(unsafe.Pointer(&g)))
	pGdipSetSmoothingMode.Call(g, 4) // AntiAlias
	pGdipSetInterpMode.Call(g, 7)    // HighQualityBicubic
	return g
}

func f32(v float32) uintptr { return uintptr(math.Float32bits(v)) }

// abgerundetes Rechteck (Radius r), z. B. Pillen-Knoepfe und Karten
func (c *canvas) round(x, y, w, h, r int, col uint32) {
	g := c.gp()
	var path, br uintptr
	pGdipCreatePath.Call(0, uintptr(unsafe.Pointer(&path)))
	X, Y, W, H, D := S(x), S(y), S(w), S(h), S(2*r)
	if D > H {
		D = H
	}
	if D > W {
		D = W
	}
	pGdipAddPathArcI.Call(path, uintptr(X), uintptr(Y), uintptr(D), uintptr(D), f32(180), f32(90))
	pGdipAddPathArcI.Call(path, uintptr(X+W-D-1), uintptr(Y), uintptr(D), uintptr(D), f32(270), f32(90))
	pGdipAddPathArcI.Call(path, uintptr(X+W-D-1), uintptr(Y+H-D-1), uintptr(D), uintptr(D), f32(0), f32(90))
	pGdipAddPathArcI.Call(path, uintptr(X), uintptr(Y+H-D-1), uintptr(D), uintptr(D), f32(90), f32(90))
	pGdipClosePathFigure.Call(path)
	pGdipCreateSolidFill.Call(argb(col), uintptr(unsafe.Pointer(&br)))
	pGdipFillPath.Call(g, br, path)
	pGdipDeleteBrush.Call(br)
	pGdipDeletePath.Call(path)
	pGdipDeleteGraphics.Call(g)
}

func (c *canvas) circle(x, y, d int, col uint32) {
	g := c.gp()
	var br uintptr
	pGdipCreateSolidFill.Call(argb(col), uintptr(unsafe.Pointer(&br)))
	pGdipFillEllipseI.Call(g, br, uintptr(S(x)), uintptr(S(y)), uintptr(S(d)), uintptr(S(d)))
	pGdipDeleteBrush.Call(br)
	pGdipDeleteGraphics.Call(g)
}

func (c *canvas) text(x, y, w, h int, s string, f uintptr, col uint32, flags uintptr) {
	r := rect{S(x), S(y), S(x + w), S(y + h)}
	pSelectObject.Call(c.dc, f)
	pSetTextColor.Call(c.dc, cref(col))
	p, _ := syscall.UTF16FromString(s)
	pDrawTextW.Call(c.dc, uintptr(unsafe.Pointer(&p[0])), uintptr(len(p)-1), uintptr(unsafe.Pointer(&r)), flags|DT_NOPREFIX)
}

// einzeilig, vertikal zentriert, mit "…" bei Ueberlaenge
func (c *canvas) line(x, y, w, h int, s string, f uintptr, col uint32, align uintptr) {
	c.text(x, y, w, h, s, f, col, align|DT_SINGLELINE|DT_VCENTER|DT_END_ELLIPSIS)
}

// Hoehe (logisch), die ein umbrochener Text in der Breite w braucht
func (c *canvas) textHeight(w int, s string, f uintptr) int {
	r := rect{0, 0, S(w), 0}
	pSelectObject.Call(c.dc, f)
	p, _ := syscall.UTF16FromString(s)
	pDrawTextW.Call(c.dc, uintptr(unsafe.Pointer(&p[0])), uintptr(len(p)-1), uintptr(unsafe.Pointer(&r)), DT_WORDBREAK|DT_CALCRECT|DT_NOPREFIX)
	return L(r.bottom)
}

// Farbstreifen im EHV-Design: Gruen | Hellgrau | Rot
func (c *canvas) stripe(x, y, w, h int) {
	t := w / 3
	c.rect(x, y, t, h, cGreen)
	c.rect(x+t, y, t, h, cLine)
	c.rect(x+2*t, y, w-2*t, h, cRed)
}

// Pillen-Knopf; hot = Maus darueber
func (c *canvas) pill(x, y, w, h int, label string, col uint32, hot bool) {
	if hot {
		col = lighter(col)
	}
	c.round(x, y, w, h, h/2, col)
	c.line(x, y, w, h, label, ui(9, true), cWhite, DT_CENTER)
}

func lighter(c uint32) uint32 {
	r, g, b := (c>>16)&0xFF, (c>>8)&0xFF, c&0xFF
	r, g, b = r+(255-r)/6, g+(255-g)/6, b+(255-b)/6
	return r<<16 | g<<8 | b
}

// ---------------------------------------------------------------- Bilder (PNG aus dem Netz)
type image struct {
	h    uintptr
	w, t int // Breite/Hoehe in Pixeln
}

func imageFromPNG(data []byte) *image {
	if len(data) == 0 {
		return nil
	}
	st, _, _ := pSHCreateMemStream.Call(uintptr(unsafe.Pointer(&data[0])), uintptr(len(data)))
	if st == 0 {
		return nil
	}
	var img uintptr
	pGdipCreateBmpStream.Call(st, uintptr(unsafe.Pointer(&img)))
	// IStream freigeben (IUnknown::Release = 3. Eintrag der vtable); GDI+ haelt eigene Referenz
	vtbl := *(*[3]uintptr)(unsafe.Pointer(*(*uintptr)(unsafe.Pointer(st))))
	syscall.SyscallN(vtbl[2], st)
	if img == 0 {
		return nil
	}
	var w, h uint32
	pGdipGetImageWidth.Call(img, uintptr(unsafe.Pointer(&w)))
	pGdipGetImageHeight.Call(img, uintptr(unsafe.Pointer(&h)))
	if w < 8 || h < 8 { // hockeydata liefert fuer fehlende Logos ein Mini-Bild
		return nil
	}
	return &image{img, int(w), int(h)}
}

// Bild proportional in ein Quadrat (Kante box) einpassen, mittig
func (c *canvas) image(im *image, x, y, box int) {
	if im == nil {
		return
	}
	scale := float64(box) / float64(im.w)
	if im.t > im.w {
		scale = float64(box) / float64(im.t)
	}
	dw, dh := float64(im.w)*scale, float64(im.t)*scale
	g := c.gp()
	pGdipDrawImageRectI.Call(g, im.h,
		uintptr(S(x)+int32(math.Round((float64(box)-dw)/2*sc))), uintptr(S(y)+int32(math.Round((float64(box)-dh)/2*sc))),
		uintptr(int32(math.Round(dw*sc))), uintptr(int32(math.Round(dh*sc))))
	pGdipDeleteGraphics.Call(g)
}

// ---------------------------------------------------------------- Programm-Icon (aus der EXE) zeichnen
var (
	pDrawIconEx = user32.NewProc("DrawIconEx")
	iconBySize  = map[int32]uintptr{}
)

// Icon in genau dieser Groesse laden (Windows waehlt die passende Stufe aus der ICO)
func appIconAt(px int32) uintptr {
	if h, ok := iconBySize[px]; ok {
		return h
	}
	hInst, _, _ := pGetModuleHandleW.Call(0)
	h, _, _ := pLoadImageW.Call(hInst, 1, 1, uintptr(px), uintptr(px), 0)
	iconBySize[px] = h
	return h
}

func (c *canvas) appIcon(x, y, size int) {
	if h := appIconAt(S(size)); h != 0 {
		pDrawIconEx.Call(c.dc, uintptr(S(x)), uintptr(S(y)), h, uintptr(S(size)), uintptr(S(size)), 0, 0, 3) // DI_NORMAL
	}
}
