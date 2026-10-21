Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$csCode = Get-Content "$PSScriptRoot\TabDraw_Host.cs" -Encoding UTF8 -Raw
Add-Type -TypeDefinition $csCode -ReferencedAssemblies "System.Windows.Forms", "System.Drawing"
[TabDraw.Program]::Main()
