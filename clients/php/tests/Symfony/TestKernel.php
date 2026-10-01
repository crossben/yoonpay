<?php

declare(strict_types=1);

namespace Yoon\Tests\Symfony;

use Psr\Log\NullLogger;
use Symfony\Bundle\FrameworkBundle\FrameworkBundle;
use Symfony\Bundle\FrameworkBundle\Kernel\MicroKernelTrait;
use Symfony\Component\DependencyInjection\Loader\Configurator\ContainerConfigurator;
use Symfony\Component\HttpKernel\Kernel;
use Symfony\Component\Routing\Loader\Configurator\RoutingConfigurator;
use Yoon\Symfony\YoonBundle;

/** The smallest Symfony application with the Yoon bundle: two webhook routes and a plain one. */
final class TestKernel extends Kernel
{
    use MicroKernelTrait;

    public const SECRET = 'symfony-test-webhook-secret-0123456789';

    private readonly string $dir;

    public function __construct()
    {
        // Not in debug mode: Symfony 6.4's debug error handler would outlive each test.
        parent::__construct('test', false);
        $this->dir = sys_get_temp_dir() . '/yoon-symfony-test-' . bin2hex(random_bytes(6));
    }

    public function registerBundles(): iterable
    {
        return [new FrameworkBundle(), new YoonBundle()];
    }

    public function getProjectDir(): string
    {
        return __DIR__;
    }

    public function getCacheDir(): string
    {
        return $this->dir . '/cache';
    }

    public function getLogDir(): string
    {
        return $this->dir . '/log';
    }

    protected function configureContainer(ContainerConfigurator $container): void
    {
        $container->extension('framework', [
            'secret' => 'test',
            'test' => true,
            'http_method_override' => false,
            'handle_all_throwables' => true,
            'php_errors' => ['log' => true],
            // Filesystem, not array: the kernel resets in-memory services between requests.
            'cache' => ['app' => 'cache.adapter.filesystem'],
            'router' => ['utf8' => true],
        ]);
        $container->extension('yoon', [
            'url' => 'https://pay.example.com/',
            'api_key' => 'yk_test',
            'webhook_secret' => self::SECRET,
        ]);
        // Quiet: the crash tests would print Symfony's default stderr log.
        $container->services()->set('logger', NullLogger::class);
        $container->services()->set(WebhookController::class)->public()->autowire()->autoconfigure();
    }

    protected function configureRoutes(RoutingConfigurator $routes): void
    {
        $routes->add('yoon_webhook', '/yoon/webhook')->controller([WebhookController::class, 'handle'])->methods(['POST']);
        $routes->add('plain', '/plain')->controller([WebhookController::class, 'plain'])->methods(['POST']);
    }
}
