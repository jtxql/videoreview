"""Offline numerical probes of two public capture samples. No USB or network.
Run: python scripts/probe_dynamic_protocol.py
No cryptographic inference is made from matches.
"""
import binascii,functools,json
SAMPLES=[('W2','D5000F519A298F7A0E35B46AEAA688FE','AA88A117DDF60FDBB1DF4AD3'),('W3','D5000F5C31DDF8954CB46DD488B05CFE','A723556032B48E020FBD5C07')]
def xor(data): return functools.reduce(int.__xor__,data,0)
def probe():
 result=[]
 for name,q,r in SAMPLES:
  q=bytes.fromhex(q);r=bytes.fromhex(r);v=q[3:-1]
  result.append(dict(sample=name,request_response_xor=bytes(a^b for a,b in zip(v,r)).hex(),response_minus_request=bytes((b-a)%256 for a,b in zip(v,r)).hex(),sum8=sum(v)%256,xor8=xor(v),crc16_ccitt_ffff=binascii.crc_hqx(v,65535),crc16_ccitt_0000=binascii.crc_hqx(v,0),last_response_byte_equals_sum8=(r[-1]==sum(v)%256),last_response_byte_equals_xor8=(r[-1]==xor(v))))
 return result
if __name__=='__main__':
 values=probe()
 print('same_positionwise_xor_across_samples:',values[0]['request_response_xor']==values[1]['request_response_xor'])
 for value in values:
  value.pop('request_response_xor')
 print(json.dumps(values,indent=2))
