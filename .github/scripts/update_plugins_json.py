import json
import os
import sys

def update_plugins_json(builds_dir, repo_url):
    plugins_json_path = os.path.join(builds_dir, 'plugins.json')
    
    if not os.path.exists(plugins_json_path):
        print(f"Error: {plugins_json_path} not found")
        sys.exit(1)

    with open(plugins_json_path, 'r', encoding='utf-8') as f:
        plugins = json.load(f)

    updated_plugins = []
    for plugin in plugins:
        name = plugin.get('name')
        internal_name = plugin.get('internalName', name)
        
        # Assume correct JAR naming convention: <InternalName>.jar
        jar_filename = f"{internal_name}.jar"
        jar_path = os.path.join(builds_dir, jar_filename)
        
        if os.path.exists(jar_path):
            jar_size = os.path.getsize(jar_path)
            plugin['jarFileSize'] = jar_size
            # Construct URL based on repo structure
            # repo_url is like "https://raw.githubusercontent.com/User/Repo/builds"
            plugin['jarUrl'] = f"{repo_url}/{jar_filename}"
            print(f"Updated {name}: Added jarUrl and jarFileSize ({jar_size} bytes)")
        else:
            print(f"Warning: JAR not found for {name} at {jar_path}")
        
        updated_plugins.append(plugin)

    with open(plugins_json_path, 'w', encoding='utf-8') as f:
        json.dump(updated_plugins, f, indent=4, ensure_ascii=False)
    
    print("Successfully updated plugins.json")

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: python3 update_plugins_json.py <builds_dir> <repo_base_url>")
        sys.exit(1)
    
    builds_dir = sys.argv[1]
    repo_base_url = sys.argv[2]
    update_plugins_json(builds_dir, repo_base_url)
