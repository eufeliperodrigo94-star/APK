# ============================================================
#  Adicione ao seu settings.py — configuração multi-banco
#  Banco principal: apostas/vendedores (existente)
#  Banco admin:    painel administrativo (novo)
# ============================================================

DATABASES = {
    # ── Banco principal (já existente — apostas, draws, users) ──
    'default': {
        'ENGINE':   'django.db.backends.postgresql',
        'NAME':     'sorte_ouro',          # seu banco atual
        'USER':     'seu_usuario',
        'PASSWORD': 'sua_senha',
        'HOST':     'localhost',
        'PORT':     '5432',
        'OPTIONS': {
            'connect_timeout': 10,
        },
    },

    # ── Banco admin (novo — auditoria, relatórios, config) ──
    'admin_db': {
        'ENGINE':   'django.db.backends.postgresql',
        'NAME':     'sorte_admin',
        'USER':     'admin_user',
        'PASSWORD': 'TROQUE_AQUI',         # mesma do admin_schema.sql
        'HOST':     'localhost',
        'PORT':     '5432',
        'OPTIONS': {
            'connect_timeout': 10,
        },
    },
}


# ============================================================
#  DATABASE ROUTER — define qual model vai para qual banco
#  Crie o arquivo: seu_projeto/routers.py
# ============================================================

# Conteúdo de routers.py:
ROUTERS_PY_CONTENT = '''
class AdminRouter:
    """
    Models no app 'admin_panel' vão para o banco 'admin_db'.
    Todos os outros modelos ficam no banco 'default'.
    """
    ADMIN_APP = 'admin_panel'   # nome do seu Django app do admin

    def db_for_read(self, model, **hints):
        if model._meta.app_label == self.ADMIN_APP:
            return 'admin_db'
        return None

    def db_for_write(self, model, **hints):
        if model._meta.app_label == self.ADMIN_APP:
            return 'admin_db'
        return None

    def allow_relation(self, obj1, obj2, **hints):
        # Permite relações dentro do mesmo banco
        db_list = ('default',) if obj1._meta.app_label != self.ADMIN_APP else ('admin_db',)
        return True

    def allow_migrate(self, db, app_label, model_name=None, **hints):
        if app_label == self.ADMIN_APP:
            return db == 'admin_db'
        return db == 'default'
'''

# Em settings.py adicione:
DATABASE_ROUTERS = ['seu_projeto.routers.AdminRouter']


# ============================================================
#  COMO RODAR AS MIGRATIONS
# ============================================================
#
#  1. Migrations do banco principal (padrão):
#     python manage.py migrate
#
#  2. Migrations do banco admin:
#     python manage.py migrate --database=admin_db
#
#  Ou rodar o SQL direto (mais simples):
#     psql -U postgres -f admin_schema.sql
#     psql -U postgres -d sorte_admin -f admin_schema.sql
#
# ============================================================


# ============================================================
#  MODELS Django para o banco admin (admin_panel/models.py)
# ============================================================

MODELS_CONTENT = '''
from django.db import models

class AdminUser(models.Model):
    name         = models.CharField(max_length=120)
    email        = models.EmailField(unique=True, null=True, blank=True)
    phone        = models.CharField(max_length=20, unique=True)
    password_hash = models.CharField(max_length=255)
    role         = models.CharField(max_length=30, default="admin")
    region_id    = models.IntegerField(null=True, blank=True)
    is_active    = models.BooleanField(default=True)
    last_login   = models.DateTimeField(null=True, blank=True)
    created_at   = models.DateTimeField(auto_now_add=True)
    updated_at   = models.DateTimeField(auto_now=True)

    class Meta:
        app_label = "admin_panel"
        db_table  = "admin_users"


class AuditLog(models.Model):
    admin        = models.ForeignKey(AdminUser, null=True, on_delete=models.SET_NULL)
    admin_name   = models.CharField(max_length=120, blank=True)
    action       = models.CharField(max_length=80)        # ex: "bet.cancel"
    target_type  = models.CharField(max_length=60, blank=True)
    target_id    = models.CharField(max_length=40, blank=True)
    detail       = models.JSONField(null=True, blank=True)
    ip_address   = models.CharField(max_length=45, blank=True)
    created_at   = models.DateTimeField(auto_now_add=True)

    class Meta:
        app_label = "admin_panel"
        db_table  = "audit_log"
        ordering  = ["-created_at"]


class FinancialSummary(models.Model):
    date          = models.DateField()
    region_id     = models.IntegerField(null=True, blank=True)
    total_bets    = models.IntegerField(default=0)
    total_amount  = models.DecimalField(max_digits=14, decimal_places=2, default=0)
    total_prizes  = models.DecimalField(max_digits=14, decimal_places=2, default=0)
    total_cancelled = models.DecimalField(max_digits=14, decimal_places=2, default=0)
    computed_at   = models.DateTimeField(auto_now=True)

    class Meta:
        app_label = "admin_panel"
        db_table  = "financial_summary"
        unique_together = [("date", "region_id")]


class SystemConfig(models.Model):
    key         = models.CharField(max_length=80, primary_key=True)
    value       = models.TextField()
    description = models.CharField(max_length=255, blank=True)
    updated_at  = models.DateTimeField(auto_now=True)

    class Meta:
        app_label = "admin_panel"
        db_table  = "system_config"


class ReportCache(models.Model):
    report_key   = models.CharField(max_length=120, unique=True)
    data         = models.JSONField()
    generated_at = models.DateTimeField(auto_now=True)
    expires_at   = models.DateTimeField()

    class Meta:
        app_label = "admin_panel"
        db_table  = "report_cache"
'''


# ============================================================
#  VIEW de login admin (admin_panel/views.py — trecho)
# ============================================================

VIEWS_LOGIN = '''
import bcrypt
import jwt
from datetime import datetime, timedelta
from django.conf import settings
from django.views.decorators.csrf import csrf_exempt
from django.http import JsonResponse
from .models import AdminUser

SECRET = settings.SECRET_KEY

@csrf_exempt
def admin_login(request):
    if request.method != "POST":
        return JsonResponse({"error": "Method not allowed"}, status=405)
    import json
    body = json.loads(request.body)
    phone    = body.get("phone", "").strip()
    password = body.get("password", "")
    try:
        user = AdminUser.objects.using("admin_db").get(phone=phone, is_active=True)
    except AdminUser.DoesNotExist:
        return JsonResponse({"detail": "Credenciais inválidas"}, status=401)

    if not bcrypt.checkpw(password.encode(), user.password_hash.encode()):
        return JsonResponse({"detail": "Credenciais inválidas"}, status=401)

    user.last_login = datetime.utcnow()
    user.save(using="admin_db")

    token = jwt.encode({
        "sub": user.id, "role": user.role,
        "exp": datetime.utcnow() + timedelta(hours=8),
        "iat": datetime.utcnow(),
    }, SECRET, algorithm="HS256")

    return JsonResponse({
        "access": token,
        "user": {"id": user.id, "name": user.name, "role": user.role, "phone": user.phone}
    })
'''


# ============================================================
#  URLs (admin_panel/urls.py)
# ============================================================

URLS_CONTENT = '''
from django.urls import path
from . import views

urlpatterns = [
    path("login/",         views.admin_login,   name="admin-login"),
    path("users/",         views.admin_users,   name="admin-users"),
    path("audit/",         views.audit_log,     name="admin-audit"),
    path("financial/",     views.financial,     name="admin-financial"),
    path("config/",        views.system_config, name="admin-config"),
]
'''

# No urls.py principal adicione:
# path("api/admin/", include("admin_panel.urls")),
