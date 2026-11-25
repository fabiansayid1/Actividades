# StudyPulse - entrega de Evidencia Integradora

## Contenido
- `Reporte_StudyPulse.docx`: reporte editable con portada, índice, explicación técnica, conclusiones modelo y guion de video.
- Código fuente Android Kotlin en `app/src/main`.
- `Evidencias_Pendientes.md`: lista de capturas y pruebas reales que deben anexarse.

## Configuración obligatoria antes de ejecutar
1. Abrir la carpeta raíz del proyecto en Android Studio.
2. En Firebase Console crear/seleccionar un proyecto Android con package `com.example.basededatos`.
3. Descargar `google-services.json` y colocarlo en `app/google-services.json`.
4. En Firebase Authentication habilitar Google y Email/Password. El acceso Email/Password existente en la interfaz usa actualmente almacenamiento local SQLite; no debe presentarse como Firebase hasta migrar las cuentas y activar el proveedor en Firebase.
5. Configurar el SHA-1/SHA-256 de la aplicación en Firebase y descargar nuevamente `google-services.json`.
6. Para Google Books, agregar `googleBooksApiKey=TU_API_KEY` a `local.properties` si el servicio requiere clave.
7. Sincronizar Gradle y ejecutar en un dispositivo Android físico o emulador con Google Play Services.

## Cambios incluidos
- Sensor de luz ambiental en la pantalla de detalle del libro. Menos de 20 lux activa un tema oscuro; la pantalla vuelve a claro al aumentar la iluminación.
- Canal de notificaciones `Libros favoritos`; al agregar un libro desde detalle, catálogo, inicio o favoritos se intenta mostrar una notificación. Android 13+ requiere permiso de notificaciones.
- Se mantiene el CRUD SQLite de favoritos por correo y el acceso a los datos de Google Books.
- Reporte y lista de evidencias para completar la entrega.

## Limitaciones que requieren validación externa
- El proveedor adicional asignado por el profesor no fue indicado. No se inventó una integración de Facebook/GitHub/Microsoft. El formulario actual de correo/contraseña registra y autentica localmente en SQLite, no con Firebase Authentication.
- No se adjuntan capturas falsas ni se afirma que se ejecutaron pruebas. Las pruebas reales deben grabarse en dispositivo después de configurar Firebase.
- No se pudo garantizar compilación en este entorno: validar con Android Studio/Gradle y corregir cualquier incompatibilidad específica del SDK instalado.

## Actividad U4: consulta de elementos guardados en BD
Se agregó un flujo explícito para consultar desde SQLite todos los libros obtenidos de Google Books y guardados en `api_books`. La pantalla Material de apoyo incluye el botón “Consultar elementos guardados en BD”. Ver `Evidencia_Consulta_BD_StudyPulse.pdf` para explicación, fragmentos de código y pasos para capturar las pruebas.
