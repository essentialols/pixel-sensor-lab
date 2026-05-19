#!/usr/bin/env python3
import sys
import re

def patch_registry(content):
    # Find the vd6282 spectral node
    # It looks like: +/dev/vd6282/0/spectral
    pattern = r'(\+/dev/vd6282/0/spectral.*?\n)((\s+[a-z_0-9]+=[^\n]+\n)+)'
    
    def replacement(match):
        header = match.group(1)
        body = match.group(2)
        
        # Check if addSensor already exists
        if 'addSensor' in body:
            return match.group(0)
            
        # Determine indentation of the body
        indent = re.match(r'^\s+', body).group(0)
        
        # Add the addSensor block
        # We use a custom type ID (0x1000b = 65547)
        new_block = (
            f"{indent}addSensor {{\n"
            f"{indent}  type = 0x1000b\n"
            f"{indent}  rate_hz = 62.5\n"
            f"{indent}  fifo_max_count = 3000\n"
            f"{indent}}}\n"
        )
        return header + body + new_block
        
    new_content = re.sub(pattern, replacement, content, flags=re.MULTILINE)
    return new_content

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: patch_registry.py <input_reg> [output_reg]")
        sys.exit(1)
        
    with open(sys.argv[1], 'r') as f:
        content = f.read()
        
    patched = patch_registry(content)
    
    if len(sys.argv) > 2:
        with open(sys.argv[2], 'w') as f:
            f.write(patched)
        print(f"Patched registry saved to {sys.argv[2]}")
    else:
        print(patched)
