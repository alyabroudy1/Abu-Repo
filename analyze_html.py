
try:
    with open('C:\\Users\\abu-o\\Abu Repo\\debug_html.txt', 'r', encoding='utf-8') as f:
        content = f.read()
    
    keyword = "watch--servers--list"
    # Search for class="watch--servers--list"
    import re
    matches = [m.start() for m in re.finditer(r'class=["\']watch--servers--list["\']', content)]
    
    if matches:
        print(f"Found {len(matches)} matches for class='watch--servers--list'")
        for index in matches:
            start = max(0, index - 100)
            end = min(len(content), index + 500)
            print(f"Match at {index}: {content[start:end]}")
    else:
        print(f"No class='{keyword}' found.")

except Exception as e:
    print(f"Error: {e}")
