//go:build windows

// Win32/GDI/GDI+-Anbindung ohne Fremdbibliotheken (nur Go-Standardbibliothek).
package main

import (
	"syscall"
	"unsafe"
)

var (
	user32   = syscall.NewLazyDLL("user32.dll")
	kernel32 = syscall.NewLazyDLL("kernel32.dll")
	gdi32    = syscall.NewLazyDLL("gdi32.dll")
	gdiplus  = syscall.NewLazyDLL("gdiplus.dll")
	shlwapi  = syscall.NewLazyDLL("shlwapi.dll")
	winmm    = syscall.NewLazyDLL("winmm.dll")
	dwmapi   = syscall.NewLazyDLL("dwmapi.dll")

	pRegisterClassExW      = user32.NewProc("RegisterClassExW")
	pCreateWindowExW       = user32.NewProc("CreateWindowExW")
	pDefWindowProcW        = user32.NewProc("DefWindowProcW")
	pDestroyWindow         = user32.NewProc("DestroyWindow")
	pGetMessageW           = user32.NewProc("GetMessageW")
	pTranslateMessage      = user32.NewProc("TranslateMessage")
	pDispatchMessageW      = user32.NewProc("DispatchMessageW")
	pPostQuitMessage       = user32.NewProc("PostQuitMessage")
	pPostMessageW          = user32.NewProc("PostMessageW")
	pShowWindow            = user32.NewProc("ShowWindow")
	pSetWindowTextW        = user32.NewProc("SetWindowTextW")
	pSetTimer              = user32.NewProc("SetTimer")
	pIsIconic              = user32.NewProc("IsIconic")
	pMessageBoxW           = user32.NewProc("MessageBoxW")
	pLoadCursorW           = user32.NewProc("LoadCursorW")
	pSetCursor             = user32.NewProc("SetCursor")
	pLoadImageW            = user32.NewProc("LoadImageW")
	pGetSystemMetrics      = user32.NewProc("GetSystemMetrics")
	pSendMessageW          = user32.NewProc("SendMessageW")
	pInvalidateRect        = user32.NewProc("InvalidateRect")
	pBeginPaint            = user32.NewProc("BeginPaint")
	pEndPaint              = user32.NewProc("EndPaint")
	pGetClientRect         = user32.NewProc("GetClientRect")
	pGetWindowRect         = user32.NewProc("GetWindowRect")
	pSetWindowPos          = user32.NewProc("SetWindowPos")
	pScreenToClient        = user32.NewProc("ScreenToClient")
	pSetForegroundWindow   = user32.NewProc("SetForegroundWindow")
	pFillRect              = user32.NewProc("FillRect")
	pDrawTextW             = user32.NewProc("DrawTextW")
	pGetDC                 = user32.NewProc("GetDC")
	pReleaseDC             = user32.NewProc("ReleaseDC")
	pTrackMouseEvent       = user32.NewProc("TrackMouseEvent")
	pSetLayeredWindowAttr  = user32.NewProc("SetLayeredWindowAttributes")
	pSetProcessDPIAware    = user32.NewProc("SetProcessDPIAware")
	pSetProcessDpiAwareCtx = user32.NewProc("SetProcessDpiAwarenessContext")
	pGetModuleHandleW      = kernel32.NewProc("GetModuleHandleW")
	pGetPrivateProfileStrW = kernel32.NewProc("GetPrivateProfileStringW")
	pWritePrivateProfileSW = kernel32.NewProc("WritePrivateProfileStringW")
	pCreateFontW           = gdi32.NewProc("CreateFontW")
	pSelectObject          = gdi32.NewProc("SelectObject")
	pDeleteObject          = gdi32.NewProc("DeleteObject")
	pDeleteDC              = gdi32.NewProc("DeleteDC")
	pCreateCompatibleDC    = gdi32.NewProc("CreateCompatibleDC")
	pCreateCompatibleBmp   = gdi32.NewProc("CreateCompatibleBitmap")
	pBitBlt                = gdi32.NewProc("BitBlt")
	pSetTextColor          = gdi32.NewProc("SetTextColor")
	pSetBkMode             = gdi32.NewProc("SetBkMode")
	pCreateSolidBrush      = gdi32.NewProc("CreateSolidBrush")
	pGetDeviceCaps         = gdi32.NewProc("GetDeviceCaps")
	pGdiplusStartup        = gdiplus.NewProc("GdiplusStartup")
	pGdipCreateFromHDC     = gdiplus.NewProc("GdipCreateFromHDC")
	pGdipDeleteGraphics    = gdiplus.NewProc("GdipDeleteGraphics")
	pGdipSetSmoothingMode  = gdiplus.NewProc("GdipSetSmoothingMode")
	pGdipSetInterpMode     = gdiplus.NewProc("GdipSetInterpolationMode")
	pGdipCreateSolidFill   = gdiplus.NewProc("GdipCreateSolidFill")
	pGdipDeleteBrush       = gdiplus.NewProc("GdipDeleteBrush")
	pGdipCreatePath        = gdiplus.NewProc("GdipCreatePath")
	pGdipDeletePath        = gdiplus.NewProc("GdipDeletePath")
	pGdipAddPathArcI       = gdiplus.NewProc("GdipAddPathArcI")
	pGdipClosePathFigure   = gdiplus.NewProc("GdipClosePathFigure")
	pGdipFillPath          = gdiplus.NewProc("GdipFillPath")
	pGdipFillEllipseI      = gdiplus.NewProc("GdipFillEllipseI")
	pGdipCreateBmpStream   = gdiplus.NewProc("GdipCreateBitmapFromStream")
	pGdipGetImageWidth     = gdiplus.NewProc("GdipGetImageWidth")
	pGdipGetImageHeight    = gdiplus.NewProc("GdipGetImageHeight")
	pGdipDrawImageRectI    = gdiplus.NewProc("GdipDrawImageRectI")
	pSHCreateMemStream     = shlwapi.NewProc("SHCreateMemStream")
	pPlaySoundW            = winmm.NewProc("PlaySoundW")
	pDwmSetWindowAttribute = dwmapi.NewProc("DwmSetWindowAttribute")
)

const (
	WS_POPUP       = 0x80000000
	WS_SYSMENU     = 0x00080000
	WS_MINIMIZEBOX = 0x00020000
	WS_EX_TOPMOST  = 0x00000008
	WS_EX_LAYERED  = 0x00080000

	CS_DBLCLKS    = 0x0008
	CS_DROPSHADOW = 0x00020000

	WM_DESTROY       = 0x0002
	WM_PAINT         = 0x000F
	WM_CLOSE         = 0x0010
	WM_ERASEBKGND    = 0x0014
	WM_SETCURSOR     = 0x0020
	WM_SETICON       = 0x0080
	WM_NCHITTEST     = 0x0084
	WM_TIMER         = 0x0113
	WM_MOUSEMOVE     = 0x0200
	WM_LBUTTONDOWN   = 0x0201
	WM_LBUTTONUP     = 0x0202
	WM_LBUTTONDBLCLK = 0x0203
	WM_MOUSEWHEEL    = 0x020A
	WM_MOUSELEAVE    = 0x02A3
	WM_APP           = 0x8000

	HTCLIENT  = 1
	HTCAPTION = 2

	SW_HIDE           = 0
	SW_SHOWNORMAL     = 1
	SW_MINIMIZE       = 6
	SW_SHOWNOACTIVATE = 4
	SW_RESTORE        = 9

	DT_CENTER       = 0x1
	DT_RIGHT        = 0x2
	DT_VCENTER      = 0x4
	DT_WORDBREAK    = 0x10
	DT_SINGLELINE   = 0x20
	DT_CALCRECT     = 0x400
	DT_NOPREFIX     = 0x800
	DT_END_ELLIPSIS = 0x8000
	DT_LEFT         = 0x0

	SWP_NOSIZE     = 0x1
	SWP_NOMOVE     = 0x2
	SWP_NOZORDER   = 0x4
	SWP_NOACTIVATE = 0x10

	MB_ICONINFORMATION = 0x40
	MB_TOPMOST         = 0x40000
)

type wndClassExW struct {
	cbSize        uint32
	style         uint32
	lpfnWndProc   uintptr
	cbClsExtra    int32
	cbWndExtra    int32
	hInstance     uintptr
	hIcon         uintptr
	hCursor       uintptr
	hbrBackground uintptr
	lpszMenuName  *uint16
	lpszClassName *uint16
	hIconSm       uintptr
}

type point struct{ x, y int32 }
type rect struct{ left, top, right, bottom int32 }

type msgT struct {
	hwnd    uintptr
	message uint32
	wParam  uintptr
	lParam  uintptr
	time    uint32
	pt      point
	private uint32
}

type paintStruct struct {
	hdc         uintptr
	fErase      int32
	rcPaint     rect
	fRestore    int32
	fIncUpdate  int32
	rgbReserved [32]byte
}

type trackMouseEventT struct {
	cbSize    uint32
	dwFlags   uint32
	hwnd      uintptr
	hoverTime uint32
}

func u16(s string) *uint16 {
	p, _ := syscall.UTF16PtrFromString(s)
	return p
}

func sendMsg(h uintptr, m uint32, w, l uintptr) uintptr {
	r, _, _ := pSendMessageW.Call(h, uintptr(m), w, l)
	return r
}

func setText(h uintptr, s string) { pSetWindowTextW.Call(h, uintptr(unsafe.Pointer(u16(s)))) }

func invalidate(h uintptr) {
	if h != 0 {
		pInvalidateRect.Call(h, 0, 0)
	}
}

func msgBox(owner uintptr, title, text string, flags uintptr) {
	pMessageBoxW.Call(owner, uintptr(unsafe.Pointer(u16(text))), uintptr(unsafe.Pointer(u16(title))), flags)
}

func registerClass(name string, proc func(hwnd, msg, wParam, lParam uintptr) uintptr) {
	hInst, _, _ := pGetModuleHandleW.Call(0)
	cur, _, _ := pLoadCursorW.Call(0, 32512) // IDC_ARROW
	wc := wndClassExW{style: CS_DBLCLKS | CS_DROPSHADOW, lpfnWndProc: syscall.NewCallback(proc),
		hInstance: hInst, hCursor: cur, lpszClassName: u16(name)}
	wc.cbSize = uint32(unsafe.Sizeof(wc))
	pRegisterClassExW.Call(uintptr(unsafe.Pointer(&wc)))
}

func createPopup(class, title string, exStyle uint32, x, y, w, h int32) uintptr {
	hInst, _, _ := pGetModuleHandleW.Call(0)
	r, _, _ := pCreateWindowExW.Call(uintptr(exStyle), uintptr(unsafe.Pointer(u16(class))),
		uintptr(unsafe.Pointer(u16(title))), uintptr(WS_POPUP|WS_SYSMENU|WS_MINIMIZEBOX),
		uintptr(x), uintptr(y), uintptr(w), uintptr(h), 0, 0, hInst, 0)
	// Windows 11: runde Ecken (auf Windows 10 ohne Wirkung)
	pref := uint32(2)
	pDwmSetWindowAttribute.Call(r, 33, uintptr(unsafe.Pointer(&pref)), 4)
	return r
}

func windowRect(h uintptr) rect {
	var r rect
	pGetWindowRect.Call(h, uintptr(unsafe.Pointer(&r)))
	return r
}

func screenW() int32 { v, _, _ := pGetSystemMetrics.Call(0); return int32(v) }
func screenH() int32 { v, _, _ := pGetSystemMetrics.Call(1); return int32(v) }

// Mauskoordinaten aus lParam (physische Pixel, vorzeichenbehaftet)
func mouseXY(lParam uintptr) (int32, int32) {
	return int32(int16(lParam & 0xFFFF)), int32(int16((lParam >> 16) & 0xFFFF))
}

func trackLeave(h uintptr) {
	t := trackMouseEventT{dwFlags: 0x2, hwnd: h} // TME_LEAVE
	t.cbSize = uint32(unsafe.Sizeof(t))
	pTrackMouseEvent.Call(uintptr(unsafe.Pointer(&t)))
}

func setHandCursor(hand bool) {
	id := uintptr(32512)
	if hand {
		id = 32649 // IDC_HAND
	}
	c, _, _ := pLoadCursorW.Call(0, id)
	pSetCursor.Call(c)
}

// INI wie in v2.0 (gleiche Datei, gleiche Schluessel)
func iniRead(file, sec, key, def string) string {
	buf := make([]uint16, 2048)
	pGetPrivateProfileStrW.Call(uintptr(unsafe.Pointer(u16(sec))), uintptr(unsafe.Pointer(u16(key))),
		uintptr(unsafe.Pointer(u16(def))), uintptr(unsafe.Pointer(&buf[0])), uintptr(len(buf)),
		uintptr(unsafe.Pointer(u16(file))))
	return syscall.UTF16ToString(buf)
}

func iniWrite(file, sec, key, val string) {
	pWritePrivateProfileSW.Call(uintptr(unsafe.Pointer(u16(sec))), uintptr(unsafe.Pointer(u16(key))),
		uintptr(unsafe.Pointer(u16(val))), uintptr(unsafe.Pointer(u16(file))))
}
