#!/usr/bin/env python3
"""Build an original 32 KiB SNES LoROM game. No third-party ROM data.
Star Catcher and this source are dedicated to the public domain (CC0).
"""
import struct
from pathlib import Path

class Asm:
    def __init__(self, base=0x8000):
        self.base, self.code, self.labels, self.fixups = base, bytearray(), {}, []
    def emit(self, *data): self.code.extend(data)
    def label(self, name): self.labels[name] = self.base + len(self.code)
    def imm(self, op, val, word=False):
        self.emit(op, val & 255)
        if word: self.emit((val >> 8) & 255)
    def absolute(self, op, address): self.emit(op, address & 255, address >> 8)
    def ref(self, op, name):
        self.emit(op); self.fixups.append((len(self.code), name, 2)); self.emit(0, 0)
    def branch(self, op, name):
        self.emit(op); self.fixups.append((len(self.code), name, 1)); self.emit(0)
    def far(self, op, name):
        inverse = {0xF0:0xD0,0xD0:0xF0,0x90:0xB0,0xB0:0x90,0x10:0x30,0x30:0x10}[op]
        self.emit(inverse, 3); self.ref(0x4C, name)
    def finish(self):
        for pos, name, size in self.fixups:
            value = self.labels[name]
            if size == 1:
                delta = value - (self.base + pos + 1)
                assert -128 <= delta <= 127, (name, delta)
                self.code[pos] = delta & 255
            else: self.code[pos:pos+2] = struct.pack('<H', value)
        return self.code

a = Asm()
# Native 65816 mode, 8-bit A and 16-bit indexes.
a.emit(0x78,0x18,0xFB); a.imm(0xC2,0x30); a.imm(0xA2,0x1FFF,True); a.emit(0x9A); a.imm(0xE2,0x20)
a.imm(0xA9,0x80); a.absolute(0x8D,0x2100)
for addr in (0x4200,0x420C,0x2105,0x2107): a.absolute(0x9C,addr)
a.imm(0xA9,1)
for addr in (0x210B,0x212C): a.absolute(0x8D,addr)
for addr in (0x210D,0x210D,0x210E,0x210E): a.absolute(0x9C,addr)
# Upload a tiny original SPC700 program and looping BRR waveform.
a.label('apu_wait'); a.absolute(0xAD,0x2140); a.imm(0xC9,0xAA); a.branch(0xD0,'apu_wait')
a.imm(0xA9,1); a.absolute(0x8D,0x2141); a.absolute(0x9C,0x2142)
a.imm(0xA9,2); a.absolute(0x8D,0x2143)
a.imm(0xA9,0xCC); a.absolute(0x8D,0x2140)
a.label('apu_ack'); a.absolute(0xCD,0x2140); a.branch(0xD0,'apu_ack')
a.imm(0xA2,0,True)
a.label('apu_upload'); a.ref(0xBD,'spc'); a.absolute(0x8D,0x2141); a.emit(0x8A); a.absolute(0x8D,0x2140)
a.label('apu_byte_ack'); a.absolute(0xCD,0x2140); a.branch(0xD0,'apu_byte_ack')
a.emit(0xE8); a.imm(0xE0,0x209,True); a.branch(0xD0,'apu_upload')
a.absolute(0x9C,0x2141); a.absolute(0x9C,0x2142); a.imm(0xA9,2); a.absolute(0x8D,0x2143)
a.emit(0x8A,0x1A); a.absolute(0x8D,0x2140)
a.label('apu_start_ack'); a.absolute(0xCD,0x2140); a.branch(0xD0,'apu_start_ack')
a.absolute(0x9C,0x2140)
# VRAM map and 2bpp graphics.
a.imm(0xA9,0x80); a.absolute(0x8D,0x2115)
a.imm(0xC2,0x20); a.imm(0xA9,0,True); a.absolute(0x8D,0x2116); a.imm(0xA2,0,True)
a.label('map_copy'); a.ref(0xBD,'tilemap'); a.absolute(0x8D,0x2118); a.emit(0xE8,0xE8); a.imm(0xE0,2048,True); a.branch(0xD0,'map_copy')
a.imm(0xA9,0x1000,True); a.absolute(0x8D,0x2116); a.imm(0xA2,0,True)
a.label('tiles_copy'); a.ref(0xBD,'tiles'); a.absolute(0x8D,0x2118); a.emit(0xE8,0xE8)
tiles_length_fix = len(a.code) + 1; a.imm(0xE0,0,True); a.branch(0xD0,'tiles_copy')
a.imm(0xE2,0x20); a.absolute(0x9C,0x2121); a.imm(0xA2,0,True)
a.label('palette_copy'); a.ref(0xBD,'palette'); a.absolute(0x8D,0x2122); a.emit(0xE8); a.imm(0xE0,8,True); a.branch(0xD0,'palette_copy')
for address,value in ((0,15),(1,15),(2,22),(3,10),(4,0),(5,0),(6,0),(7,0),(10,0)):
    a.imm(0xA9,value); a.emit(0x85,address)
a.imm(0xA9,1); a.absolute(0x8D,0x4200) # Auto joypad, no NMI.
a.imm(0xA9,15); a.absolute(0x8D,0x2100)
a.label('frame')
a.label('vblank'); a.absolute(0x2C,0x4210); a.branch(0x10,'vblank')
a.label('joy_wait'); a.absolute(0xAD,0x4212); a.imm(0x29,1); a.branch(0xD0,'joy_wait')
a.emit(0xE6,6); a.absolute(0xAD,0x4219); a.emit(0x85,7)
# Diagnostic SRAM is written by the actual emulated CPU, useful for integration tests.
for direct,offset in ((0,0),(1,1),(4,2),(7,3),(6,4)):
    a.emit(0xA5,direct,0x8F,offset,0,0x70)
a.emit(0xA5,7); a.imm(0x29,0x80); a.branch(0xF0,'sound_timer')
a.imm(0xA9,1); a.branch(0x80,'sound_write')
a.label('sound_timer'); a.emit(0xA5,10); a.branch(0xF0,'sound_write')
a.emit(0xC6,10); a.imm(0xA9,1)
a.label('sound_write'); a.absolute(0x8D,0x2140)
a.emit(0xE6,5,0xA5,5); a.imm(0xC9,5); a.far(0x90,'frame')
a.emit(0x64,5,0xA5,0,0x85,8,0xA5,1,0x85,9)
for name,bit,axis,limit,operation in (
    ('up',8,1,5,0xC6),('down',4,1,25,0xE6),('left',2,0,1,0xC6),('right',1,0,30,0xE6)):
    a.emit(0xA5,7); a.imm(0x29,bit); a.branch(0xF0,'no_'+name)
    a.emit(0xA5,axis); a.imm(0xC9,limit); a.branch(0xF0,'no_'+name)
    a.emit(operation,axis); a.label('no_'+name)
a.emit(0xA5,0,0xC5,2); a.branch(0xD0,'draw_all')
a.emit(0xA5,1,0xC5,3); a.branch(0xD0,'draw_all')
a.emit(0xE6,4,0xA5,4); a.imm(0xC9,100); a.branch(0xD0,'score_ok'); a.emit(0x64,4)
a.label('score_ok')
a.emit(0xA5,6); a.imm(0x29,15); a.emit(0x18); a.imm(0x69,6); a.emit(0x85,2)
a.emit(0xA5,4); a.imm(0x29,15); a.emit(0x18); a.imm(0x69,6); a.emit(0x85,3)
a.imm(0xA9,8); a.emit(0x85,10)
a.label('draw_all')
for x,y,tile in ((8,9,0),(2,3,3),(0,1,2)):
    a.emit(0xA5,x,0x85,13,0xA5,y,0x85,14); a.imm(0xA9,tile); a.emit(0x85,15); a.ref(0x20,'draw')
# Decimal score.
a.emit(0x64,11,0xA5,4)
a.label('score_tens'); a.imm(0xC9,10); a.branch(0x90,'score_ones'); a.emit(0x38); a.imm(0xE9,10); a.emit(0xE6,11); a.branch(0x80,'score_tens')
a.label('score_ones'); a.emit(0x18); a.imm(0x69,4); a.emit(0x85,12)
a.imm(0xC2,0x20); a.imm(0xA9,3*32+17,True); a.absolute(0x8D,0x2116); a.imm(0xE2,0x20)
a.emit(0xA5,11,0x18); a.imm(0x69,4); a.absolute(0x8D,0x2118); a.absolute(0x9C,0x2119)
a.emit(0xA5,12); a.absolute(0x8D,0x2118); a.absolute(0x9C,0x2119)
a.ref(0x4C,'frame')
a.label('draw')
a.emit(0xA5,14); a.imm(0xC2,0x20); a.imm(0x29,255,True); a.emit(0x0A,0x0A,0x0A,0x0A,0x0A,0x85,16)
a.imm(0xE2,0x20); a.emit(0xA5,13); a.imm(0xC2,0x20); a.imm(0x29,255,True); a.emit(0x18,0x65,16)
a.absolute(0x8D,0x2116); a.imm(0xE2,0x20); a.emit(0xA5,15); a.absolute(0x8D,0x2118); a.absolute(0x9C,0x2119); a.emit(0x60)
a.label('interrupt'); a.emit(0x40)

spc = Asm(0x0200)
def dsp(reg,value): spc.emit(0x8F,reg,0xF2,0x8F,value,0xF3)
for reg,val in ((0x6C,0x20),(0x0C,0x40),(0x1C,0x40),(0x5D,3),(0x00,0x40),(0x01,0x40),
                (0x02,0),(0x03,4),(0x04,0),(0x05,0),(0x06,0),(0x07,0x50),(0x5C,1)):
    dsp(reg,val)
spc.emit(0x8F,0,0) # MOV $00,#0.
spc.label('loop'); spc.emit(0xE4,0xF4,0x64,0); spc.branch(0xF0,'loop')
spc.emit(0xC4,0,0x28,1); spc.branch(0xF0,'off')
dsp(0x5C,0); dsp(0x4C,1); spc.branch(0x2F,'loop')
spc.label('off'); dsp(0x5C,1); spc.branch(0x2F,'loop')
block = bytearray(0x209); program = spc.finish(); assert len(program) < 0x100
block[:len(program)] = program
block[0x100:0x104] = b'\x00\x04\x00\x04'
block[0x200:0x209] = bytes((0xB3,0x03,0x67,0x76,0x30,0x0D,0xA9,0x9A,0xD0))
a.label('spc'); a.code.extend(block)

# 5x7 glyphs expanded to SNES 2bpp tiles.
font = {
'A':['01110','10001','10001','11111','10001','10001','10001'],
'B':['11110','10001','10001','11110','10001','10001','11110'],
'C':['01111','10000','10000','10000','10000','10000','01111'],
'D':['11110','10001','10001','10001','10001','10001','11110'],
'E':['11111','10000','10000','11110','10000','10000','11111'],
'F':['11111','10000','10000','11110','10000','10000','10000'],
'G':['01111','10000','10000','10111','10001','10001','01111'],
'H':['10001','10001','10001','11111','10001','10001','10001'],
'I':['11111','00100','00100','00100','00100','00100','11111'],
'J':['00111','00010','00010','00010','10010','10010','01100'],
'K':['10001','10010','10100','11000','10100','10010','10001'],
'L':['10000','10000','10000','10000','10000','10000','11111'],
'M':['10001','11011','10101','10101','10001','10001','10001'],
'N':['10001','11001','10101','10011','10001','10001','10001'],
'O':['01110','10001','10001','10001','10001','10001','01110'],
'P':['11110','10001','10001','11110','10000','10000','10000'],
'Q':['01110','10001','10001','10001','10101','10010','01101'],
'R':['11110','10001','10001','11110','10100','10010','10001'],
'S':['01111','10000','10000','01110','00001','00001','11110'],
'T':['11111','00100','00100','00100','00100','00100','00100'],
'U':['10001','10001','10001','10001','10001','10001','01110'],
'V':['10001','10001','10001','10001','10001','01010','00100'],
'W':['10001','10001','10001','10101','10101','10101','01010'],
'X':['10001','10001','01010','00100','01010','10001','10001'],
'Y':['10001','10001','01010','00100','00100','00100','00100'],
'Z':['11111','00001','00010','00100','01000','10000','11111'],
'0':['01110','10001','10011','10101','11001','10001','01110'],
'1':['00100','01100','00100','00100','00100','00100','01110'],
'2':['01110','10001','00001','00010','00100','01000','11111'],
'3':['11110','00001','00001','01110','00001','00001','11110'],
'4':['00010','00110','01010','10010','11111','00010','00010'],
'5':['11111','10000','10000','11110','00001','00001','11110'],
'6':['01110','10000','10000','11110','10001','10001','01110'],
'7':['11111','00001','00010','00100','01000','01000','01000'],
'8':['01110','10001','10001','01110','10001','10001','01110'],
'9':['01110','10001','10001','01111','00001','00001','01110'],
'-':['00000','00000','00000','11111','00000','00000','00000'],
}
def tile(rows):
    data=bytearray()
    for row in rows:
        data.extend((sum((int(p)&1)<<(7-x) for x,p in enumerate(row)),sum(((int(p)>>1)&1)<<(7-x) for x,p in enumerate(row))))
    return data
tiles = bytearray(16) # Space.
tiles += tile(['11111111','10000001','10000001','10000001','10000001','10000001','10000001','11111111'])
tiles += tile(['00000000','00333300','03111130','03111130','03111130','03111130','00333300','00000000'])
tiles += tile(['00020000','00222000','22222220','02222200','00222000','02202200','02000200','00000000'])
glyphs={' ':0}
for char in '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-':
    glyphs[char]=len(tiles)//16
    rows=['0'+s.replace('1','3')+'00' for s in font[char]]+['00000000']
    tiles+=tile(rows)
a.code[tiles_length_fix:tiles_length_fix+2] = struct.pack('<H',len(tiles))
a.label('tiles'); a.code.extend(tiles)
screen=[0]*1024
def write(x,y,text):
    for i,c in enumerate(text): screen[y*32+x+i]=glyphs[c]
write(10,1,'STAR CATCHER');write(11,3,'SCORE 00');write(6,27,'D-PAD MOVE - B SOUND')
for x in range(32): screen[4*32+x]=1;screen[26*32+x]=1
for y in range(5,26): screen[y*32]=1;screen[y*32+31]=1
screen[15*32+15]=2;screen[10*32+22]=3
a.label('tilemap'); a.code.extend(struct.pack('<1024H',*screen))
a.label('palette'); a.code.extend(struct.pack('<4H',0x1000,0x7F45,0x133F,0x7FFF))
rom=bytearray([0xFF]*32768); code=a.finish();assert len(code)<0x7FC0
rom[:len(code)]=code
rom[0x7FC0:0x7FD5]=b'SNES TV STAR CATCHER'.ljust(21)
rom[0x7FD5:0x7FDC]=bytes((0x20,0x02,0x05,0x01,0x01,0x33,0))
rom[0x7FDC:0x7FE0]=b'\0'*4
for off in range(0x7FE0,0x8000,2): struct.pack_into('<H',rom,off,a.labels['interrupt'])
struct.pack_into('<H',rom,0x7FFC,0x8000)
checksum=(sum(rom)+0x1FE)&0xFFFF
struct.pack_into('<HH',rom,0x7FDC,checksum^0xFFFF,checksum)
destination=Path(__file__).resolve().parents[1]/'app/src/main/assets/star-catcher.sfc'
assert len(rom) == 32768
destination.parent.mkdir(parents=True,exist_ok=True);destination.write_bytes(rom)
print(f'Built original Star Catcher SNES game: {len(rom)} bytes, {len(code)} bytes code/assets.')
