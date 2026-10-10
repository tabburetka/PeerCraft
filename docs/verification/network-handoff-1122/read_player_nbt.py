"""Inspect selected fields of a trusted local Minecraft player fixture save."""
import gzip, json, struct, sys
from pathlib import Path
class Reader:
    def __init__(self,data): self.data=data;self.pos=0
    def read(self,n):
        if n<0 or self.pos+n>len(self.data): raise ValueError('Truncated NBT')
        value=self.data[self.pos:self.pos+n];self.pos+=n;return value
    def num(self,fmt):return struct.unpack('>'+fmt,self.read(struct.calcsize('>'+fmt)))[0]
    def string(self):return self.read(self.num('H')).decode('utf-8')
    def tag(self,t):
        if t in range(1,7):return self.num({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[t])
        if t==7:return list(self.read(self.num('i')))
        if t==8:return self.string()
        if t==9:
            subtype=self.num('B');return [self.tag(subtype) for _ in range(self.num('i'))]
        if t==10:
            out={}
            while True:
                subtype=self.num('B')
                if subtype==0:return out
                key=self.string();out[key]=self.tag(subtype)
        if t in (11,12):return [self.num('i' if t==11 else 'q') for _ in range(self.num('i'))]
        raise ValueError('Unknown NBT type '+str(t))
if __name__=='__main__':
    reader=Reader(gzip.decompress(Path(sys.argv[1]).read_bytes()))
    kind=reader.num('B');reader.string();data=reader.tag(kind)
    print(json.dumps({k:data.get(k) for k in ['Inventory','EnderItems','XpTotal','UUIDMost','UUIDLeast','Pos']},indent=2))
